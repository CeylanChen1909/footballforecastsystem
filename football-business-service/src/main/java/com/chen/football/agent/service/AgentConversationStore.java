package com.chen.football.agent.service;

import com.chen.football.agent.dto.AgentMessage;
import com.chen.football.common.service.RedisCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Durable Agent conversations with Redis as a fast read-through cache. */
@Slf4j
@Service
public class AgentConversationStore {

    private static final long DEFAULT_TTL_SECONDS = Duration.ofDays(7).toSeconds();
    private static final int MAX_MESSAGES = 40;
    private static final Map<String, Object> SESSION_LOCKS = new ConcurrentHashMap<>();

    private final RedisCacheService cacheService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentConversationStore(RedisCacheService cacheService,
                                  JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper) {
        this.cacheService = cacheService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void ensureTable() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_agent_conversation (" +
                    "session_id VARCHAR(64) NOT NULL PRIMARY KEY," +
                    "user_id BIGINT NOT NULL," +
                    "title VARCHAR(128) NOT NULL DEFAULT '新会话'," +
                    "preview VARCHAR(512) NULL," +
                    "messages_json MEDIUMTEXT NOT NULL," +
                    "metadata_json MEDIUMTEXT NULL," +
                    "created_at DATETIME NOT NULL," +
                    "updated_at DATETIME NOT NULL," +
                    "INDEX idx_agent_conversation_user_updated (user_id, updated_at)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (Exception ex) {
            log.warn("Agent 会话持久化表初始化失败，将继续使用 Redis: {}", ex.getMessage());
        }
    }

    public List<AgentMessage> recentMessages(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        ConversationSnapshot snapshot = loadSnapshot(sessionId);
        if (snapshot == null || snapshot.messages == null || snapshot.messages.isEmpty()) return List.of();
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 10 : limit, MAX_MESSAGES));
        int from = Math.max(0, snapshot.messages.size() - safeLimit);
        return new ArrayList<>(snapshot.messages.subList(from, snapshot.messages.size()));
    }

    public Map<String, Object> snapshot(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("sessionId", null, "messages", List.of(), "metadata", Map.of());
        }
        ConversationSnapshot snapshot = loadSnapshot(sessionId);
        if (snapshot == null) {
            return Map.of("sessionId", sessionId, "messages", List.of(), "metadata", Map.of());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", snapshot.sessionId);
        result.put("messages", snapshot.messages == null ? List.of() : snapshot.messages);
        result.put("metadata", snapshot.metadata == null ? Map.of() : snapshot.metadata);
        result.put("lastUpdatedAt", snapshot.lastUpdatedAt);
        result.put("messageCount", snapshot.messages == null ? 0 : snapshot.messages.size());
        return result;
    }

    public void append(String sessionId, List<AgentMessage> messages, Map<String, Object> metadata) {
        append(sessionId, messages, metadata, null);
    }

    public void append(String sessionId, List<AgentMessage> messages, Map<String, Object> metadata, Long userId) {
        if (sessionId == null || sessionId.isBlank() || messages == null || messages.isEmpty()) return;
        Object lock = SESSION_LOCKS.computeIfAbsent(sessionId, ignored -> new Object());
        synchronized (lock) {
            try {
                ConversationSnapshot snapshot = loadSnapshot(sessionId);
                if (snapshot == null) {
                    snapshot = new ConversationSnapshot();
                    snapshot.sessionId = sessionId;
                    snapshot.messages = new ArrayList<>();
                }
                if (snapshot.messages == null) snapshot.messages = new ArrayList<>();
                List<AgentMessage> nextMessages = new ArrayList<>(messages);
                if (metadata != null && !metadata.isEmpty()) {
                    for (int i = nextMessages.size() - 1; i >= 0; i--) {
                        AgentMessage message = nextMessages.get(i);
                        if (message != null && "assistant".equalsIgnoreCase(message.role())) {
                            nextMessages.set(i, AgentMessage.assistant(message.content(), metadata));
                            break;
                        }
                    }
                }
                snapshot.messages.addAll(nextMessages);
                if (snapshot.messages.size() > MAX_MESSAGES) {
                    snapshot.messages = new ArrayList<>(snapshot.messages.subList(snapshot.messages.size() - MAX_MESSAGES, snapshot.messages.size()));
                }
                snapshot.metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
                snapshot.lastUpdatedAt = Instant.now().toString();
                cacheService.set(key(sessionId), snapshot, DEFAULT_TTL_SECONDS);
                persistSnapshot(sessionId, userId, snapshot, null, null);
            } finally {
                SESSION_LOCKS.remove(sessionId, lock);
            }
        }
    }

    @Scheduled(fixedDelay = 86_400_000L, initialDelay = 86_400_000L)
    void purgeExpiredDatabaseConversations() {
        try {
            jdbcTemplate.update("DELETE FROM t_agent_conversation WHERE updated_at < DATE_SUB(NOW(), INTERVAL 30 DAY)");
        } catch (Exception ex) {
            log.debug("清理过期 Agent 会话失败: {}", ex.getMessage());
        }
    }

    public void registerSession(Long userId, String sessionId, String title, String preview) {
        if (userId == null || sessionId == null || sessionId.isBlank()) return;
        ConversationSnapshot snapshot = loadSnapshot(sessionId);
        if (snapshot == null) {
            snapshot = new ConversationSnapshot();
            snapshot.sessionId = sessionId;
            snapshot.messages = new ArrayList<>();
            snapshot.metadata = new LinkedHashMap<>();
            snapshot.lastUpdatedAt = Instant.now().toString();
        }
        persistSnapshot(sessionId, userId, snapshot, title, preview);
        SessionIndex index = cacheService.get(indexKey(userId), SessionIndex.class);
        if (index == null) index = new SessionIndex();
        if (index.items == null) index.items = new ArrayList<>();
        SessionMeta item = index.items.stream().filter(s -> sessionId.equals(s.id)).findFirst().orElse(null);
        if (item == null) {
            item = new SessionMeta();
            item.id = sessionId;
            index.items.add(item);
        }
        item.title = title == null || title.isBlank() ? "新会话" : title.trim();
        item.preview = preview;
        item.updatedAt = Instant.now().toString();
        index.items.sort((a, b) -> String.valueOf(b.updatedAt).compareTo(String.valueOf(a.updatedAt)));
        if (index.items.size() > 30) index.items = new ArrayList<>(index.items.subList(0, 30));
        cacheService.set(indexKey(userId), index, DEFAULT_TTL_SECONDS);
    }

    public List<SessionMeta> listSessions(Long userId) {
        if (userId == null) return List.of();
        try {
            return jdbcTemplate.query("SELECT session_id,title,preview,updated_at FROM t_agent_conversation WHERE user_id=? ORDER BY updated_at DESC LIMIT 30",
                    (rs, rowNum) -> {
                        SessionMeta item = new SessionMeta();
                        item.id = rs.getString("session_id");
                        item.title = rs.getString("title");
                        item.preview = rs.getString("preview");
                        item.updatedAt = rs.getTimestamp("updated_at").toInstant().toString();
                        return item;
                    }, userId);
        } catch (Exception ex) {
            log.debug("读取 Agent 会话数据库失败，回退 Redis: {}", ex.getMessage());
            SessionIndex index = cacheService.get(indexKey(userId), SessionIndex.class);
            return index == null || index.items == null ? List.of() : new ArrayList<>(index.items);
        }
    }

    public boolean hasConversation(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_agent_conversation WHERE session_id=?", Integer.class, sessionId);
            // A successful DB lookup with zero rows is authoritative.  Do not
            // resurrect a deleted transcript from the Redis cache.
            return count != null && count > 0;
        } catch (Exception ignored) { }
        return cacheService.get(key(sessionId), ConversationSnapshot.class) != null;
    }

    public boolean ownsSession(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) return false;
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_agent_conversation WHERE session_id=? AND user_id=?", Integer.class, sessionId, userId);
            if (count != null) return count > 0;
        } catch (Exception ignored) { }
        SessionIndex index = cacheService.get(indexKey(userId), SessionIndex.class);
        return index != null && index.items != null && index.items.stream().anyMatch(item -> sessionId.equals(item.id));
    }

    public boolean renameSession(Long userId, String sessionId, String title) {
        if (userId == null || sessionId == null || sessionId.isBlank() || title == null || title.isBlank()) return false;
        String safeTitle = title.trim().replaceAll("\\s+", " ");
        if (safeTitle.length() > 64) safeTitle = safeTitle.substring(0, 64);
        try {
            return jdbcTemplate.update("UPDATE t_agent_conversation SET title=?, updated_at=? WHERE session_id=? AND user_id=?", safeTitle, LocalDateTime.now(), sessionId, userId) > 0;
        } catch (Exception ex) {
            SessionIndex index = cacheService.get(indexKey(userId), SessionIndex.class);
            if (index == null || index.items == null) return false;
            for (SessionMeta item : index.items) {
                if (sessionId.equals(item.id)) {
                    item.title = safeTitle;
                    item.updatedAt = Instant.now().toString();
                    cacheService.set(indexKey(userId), index, DEFAULT_TTL_SECONDS);
                    return true;
                }
            }
            return false;
        }
    }

    public void deleteSession(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) return;
        try { jdbcTemplate.update("DELETE FROM t_agent_conversation WHERE session_id=? AND user_id=?", sessionId, userId); }
        catch (Exception ignored) { }
        SessionIndex index = cacheService.get(indexKey(userId), SessionIndex.class);
        if (index != null && index.items != null) {
            index.items.removeIf(item -> sessionId.equals(item.id));
            cacheService.set(indexKey(userId), index, DEFAULT_TTL_SECONDS);
        }
        cacheService.evict(key(sessionId));
    }

    private ConversationSnapshot loadSnapshot(String sessionId) {
        boolean databaseReadable = false;
        try {
            List<ConversationSnapshot> rows = jdbcTemplate.query("SELECT session_id,messages_json,metadata_json,updated_at FROM t_agent_conversation WHERE session_id=? LIMIT 1",
                    (rs, rowNum) -> {
                        try {
                            ConversationSnapshot snapshot = objectMapper.readValue(rs.getString("messages_json"), ConversationSnapshot.class);
                            snapshot.sessionId = rs.getString("session_id");
                            snapshot.lastUpdatedAt = rs.getTimestamp("updated_at").toInstant().toString();
                            String metadata = rs.getString("metadata_json");
                            if (metadata != null && !metadata.isBlank()) snapshot.metadata = objectMapper.readValue(metadata, new TypeReference<Map<String, Object>>() { });
                            return snapshot;
                        } catch (Exception ex) {
                            throw new IllegalStateException("Agent 会话 JSON 解析失败", ex);
                        }
                    }, sessionId);
            databaseReadable = true;
            if (!rows.isEmpty()) {
                ConversationSnapshot snapshot = rows.get(0);
                cacheService.set(key(sessionId), snapshot, DEFAULT_TTL_SECONDS);
                return snapshot;
            }
        } catch (Exception ex) {
            log.debug("读取 Agent 会话数据库失败: {}", ex.getMessage());
        }
        if (databaseReadable) {
            cacheService.evict(key(sessionId));
            return null;
        }
        return cacheService.get(key(sessionId), ConversationSnapshot.class);
    }

    private void persistSnapshot(String sessionId, Long userId, ConversationSnapshot snapshot, String title, String preview) {
        if (userId == null || sessionId == null || snapshot == null) return;
        try {
            String messagesJson = objectMapper.writeValueAsString(snapshot);
            String metadataJson = objectMapper.writeValueAsString(snapshot.metadata == null ? Map.of() : snapshot.metadata);
            LocalDateTime now = LocalDateTime.now();
            String safeTitle = title == null || title.isBlank() ? "新会话" : title.trim().replaceAll("\\s+", " ");
            if (safeTitle.length() > 64) safeTitle = safeTitle.substring(0, 64);
            String safePreview = preview == null ? null : preview.trim().replaceAll("\\s+", " ");
            if (safePreview != null && safePreview.length() > 512) safePreview = safePreview.substring(0, 512);
            jdbcTemplate.update("INSERT INTO t_agent_conversation (session_id,user_id,title,preview,messages_json,metadata_json,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?) " +
                            "ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),title=CASE WHEN VALUES(title)='新会话' THEN title ELSE VALUES(title) END,preview=COALESCE(VALUES(preview),preview),messages_json=VALUES(messages_json),metadata_json=VALUES(metadata_json),updated_at=VALUES(updated_at)",
                    sessionId, userId, safeTitle, safePreview, messagesJson, metadataJson, now, now);
        } catch (Exception ex) {
            log.debug("写入 Agent 会话数据库失败，将保留 Redis: {}", ex.getMessage());
        }
    }

    private String key(String sessionId) { return "agent:conversation:" + sessionId; }
    private String indexKey(Long userId) { return "agent:sessions:user:" + userId; }

    public static class SessionIndex { public List<SessionMeta> items = new ArrayList<>(); }

    public static class SessionMeta {
        public String id;
        public String title;
        public String preview;
        public String updatedAt;
    }

    public static class ConversationSnapshot {
        public String sessionId;
        public List<AgentMessage> messages = new ArrayList<>();
        public Map<String, Object> metadata = new LinkedHashMap<>();
        public String lastUpdatedAt;
    }
}
