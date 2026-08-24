package com.chen.football.card.controller;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 幻想远征 V2：服务端权威的分支地图 + 节点事件 + 战术战斗状态机。
 * 一局最多携带三张已有角色卡；每个招募/战斗奖励节点优先提供未拥有的新角色，
 * 战斗结束后可以选择角色或局内强化。点数奖励没有每日上限，但每局流水严格幂等。
 */
@RestController
@RequestMapping("/api/card-rogue")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "card-rogue", name = "enabled", havingValue = "true")
public class CardRogueController {

    private static final int MAX_CARRY = 3;
    private static final int MAX_BATTLE_CARDS = 5;
    private static final int MAX_LEVEL = 8;
    private static final int MIN_CHOICE_COUNT = 3;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> ACTIVE_STATUSES = Set.of("MAP", "EVENT", "CHOICE", "BATTLE", "REWARD");
    private static final Set<String> TACTICS = Set.of("PRESS", "CONTROL", "COUNTER", "DIRECT");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void ensureTables() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_card_rogue_run (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, status VARCHAR(16) NOT NULL, " +
                "current_level INT NOT NULL DEFAULT 1, cleared_levels INT NOT NULL DEFAULT 0, max_level INT NOT NULL DEFAULT 8, " +
                "seed BIGINT NOT NULL, reward_points INT NOT NULL DEFAULT 0, claimed_at DATETIME NULL, " +
                "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id), KEY idx_fc_card_rogue_run_user (user_id, status), KEY idx_fc_card_rogue_run_updated (updated_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("fc_card_rogue_run", "current_node_id", "VARCHAR(32) NULL");
        addColumnIfMissing("fc_card_rogue_run", "node_type", "VARCHAR(16) NULL");
        addColumnIfMissing("fc_card_rogue_run", "choice_context", "VARCHAR(16) NULL");
        addColumnIfMissing("fc_card_rogue_run", "map_json", "MEDIUMTEXT NULL");
        addColumnIfMissing("fc_card_rogue_run", "encounter_json", "TEXT NULL");
        addColumnIfMissing("fc_card_rogue_run", "event_json", "TEXT NULL");
        addColumnIfMissing("fc_card_rogue_run", "boosts_json", "TEXT NULL");
        addColumnIfMissing("fc_card_rogue_run", "last_battle_json", "TEXT NULL");
        addColumnIfMissing("fc_card_rogue_run", "morale", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("fc_card_rogue_run", "run_version", "INT NOT NULL DEFAULT 1");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_card_rogue_roster (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, run_id BIGINT NOT NULL, user_card_id BIGINT NULL, catalog_id BIGINT NULL, " +
                "source VARCHAR(16) NOT NULL, snapshot_json MEDIUMTEXT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (id), UNIQUE KEY uk_fc_card_rogue_roster_card (run_id, catalog_id), KEY idx_fc_card_rogue_roster_run (run_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS fc_card_rogue_choice (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, run_id BIGINT NOT NULL, level_no INT NOT NULL, choice_no INT NOT NULL, " +
                "catalog_id BIGINT NOT NULL DEFAULT 0, choice_type VARCHAR(16) NOT NULL DEFAULT 'ROLE', snapshot_json MEDIUMTEXT NOT NULL, selected TINYINT NOT NULL DEFAULT 0, " +
                "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), " +
                "UNIQUE KEY uk_fc_card_rogue_choice (run_id, level_no, choice_no), KEY idx_fc_card_rogue_choice_run (run_id, level_no)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("fc_card_rogue_choice", "choice_type", "VARCHAR(16) NOT NULL DEFAULT 'ROLE'");
        try { jdbcTemplate.update("UPDATE fc_card_rogue_run SET status = 'ABANDONED' WHERE map_json IS NULL AND status IN ('CHOICE','BATTLE','MAP','EVENT','REWARD')"); } catch (Exception ignored) { }
    }

    @GetMapping("/state")
    public ApiResponse<Map<String, Object>> state() {
        return ApiResponse.ok(stateForUser(requireUser()));
    }

    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> history() {
        Long userId = requireUser();
        return ApiResponse.ok(jdbcTemplate.queryForList("SELECT id, status, cleared_levels AS clearedLevels, max_level AS maxLevel, reward_points AS rewardPoints, created_at AS createdAt, claimed_at AS claimedAt FROM fc_card_rogue_run WHERE user_id = ? AND status NOT IN ('ABANDONED','MAP','EVENT','CHOICE','BATTLE','REWARD') ORDER BY id DESC LIMIT 30", userId));
    }

    @PostMapping("/runs")
    @Transactional
    public ApiResponse<Map<String, Object>> start(@RequestBody(required = false) Map<String, Object> body) {
        Long userId = requireUser();
        Integer active = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_card_rogue_run WHERE user_id = ? AND status IN ('MAP','EVENT','CHOICE','BATTLE','REWARD')", Integer.class, userId);
        if (active != null && active > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "你已有一局进行中的幻想远征");
        List<Long> carryIds = readIds(body == null ? null : body.get("carriedCardIds"));
        if (carryIds.size() > MAX_CARRY) throw new IllegalArgumentException("最多携带 3 张已有角色卡");
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Long cardId : carryIds) {
            try {
                Map<String, Object> card = jdbcTemplate.queryForMap("SELECT c.id, c.player_name AS name, c.catalog_id AS catalogId, c.position, c.photo_url AS photoUrl, c.bio_summary AS description, c.overall, c.pace, c.shooting, c.passing, c.dribbling, c.defending, c.physical, c.tags_json AS tagsJson, c.skills_json AS skillsJson, c.traits_json AS traitsJson FROM fc_player_cards c JOIN fc_persona_inventory i ON i.card_id = c.id WHERE c.id = ? AND i.user_id = ? AND c.card_type = 'CUSTOM_PERSONA' AND c.catalog_id IS NOT NULL", cardId, userId);
                cards.add(normalizeSnapshot(card));
            } catch (Exception error) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "携带的角色卡不存在或不属于当前用户");
            }
        }
        long seed = RANDOM.nextLong();
        Map<String, Object> expeditionMap = createMap(seed);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO fc_card_rogue_run (user_id, status, current_level, cleared_levels, max_level, seed, map_json, boosts_json, morale) VALUES (?, 'MAP', 1, 0, ?, ?, ?, '[]', 0)", new String[]{"id"});
            statement.setLong(1, userId); statement.setInt(2, MAX_LEVEL); statement.setLong(3, seed); statement.setString(4, json(expeditionMap)); return statement;
        }, keyHolder);
        Long runId = keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
        if (runId == null) throw new IllegalStateException("远征创建失败");
        for (int i = 0; i < cards.size(); i++) {
            Map<String, Object> card = cards.get(i);
            jdbcTemplate.update("INSERT INTO fc_card_rogue_roster (run_id, user_card_id, catalog_id, source, snapshot_json) VALUES (?, ?, ?, 'CARRIED', ?)", runId, carryIds.get(i), number(card.get("catalogId")), json(card));
        }
        return ApiResponse.ok(stateForRun(userId, runId));
    }

    @PostMapping("/runs/{runId}/node")
    @Transactional
    public ApiResponse<Map<String, Object>> selectNode(@PathVariable Long runId, @RequestBody Map<String, Object> body) {
        Long userId = requireUser();
        Map<String, Object> run = lockRun(userId, runId);
        if (!"MAP".equals(text(run.get("status")))) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前不在地图选择阶段");
        String nodeId = text(body == null ? null : body.get("nodeId"));
        Map<String, Object> map = parseMap(text(run.get("map_json")));
        Map<String, Object> node = nodeById(map, nodeId);
        if (node == null || !"AVAILABLE".equals(text(node.get("status")))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该节点不可选择，请刷新地图");
        node.put("status", "CURRENT"); map.put("currentNodeId", nodeId);
        String type = text(node.get("type")); int level = intValue(node.get("level"), 1);
        jdbcTemplate.update("UPDATE fc_card_rogue_run SET current_node_id = ?, node_type = ?, current_level = ?, map_json = ?, encounter_json = NULL, event_json = NULL, choice_context = NULL, status = ?, run_version = run_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?", nodeId, type, level, json(map), statusForNode(type), runId, userId);
        if ("RECRUIT".equals(type)) generateRoleChoices(runId, level, longValue(run.get("seed")), rosterCatalogIds(runId), userId);
        if ("BATTLE".equals(type) || "ELITE".equals(type) || "BOSS".equals(type)) jdbcTemplate.update("UPDATE fc_card_rogue_run SET encounter_json = ? WHERE id = ? AND user_id = ?", json(encounterFor(node, run)), runId, userId);
        if ("REST".equals(type) || "EVENT".equals(type)) jdbcTemplate.update("UPDATE fc_card_rogue_run SET event_json = ? WHERE id = ? AND user_id = ?", json(eventFor(node, run)), runId, userId);
        return ApiResponse.ok(stateForRun(userId, runId));
    }

    @PostMapping("/runs/{runId}/choice")
    @Transactional
    public ApiResponse<Map<String, Object>> choose(@PathVariable Long runId, @RequestBody Map<String, Object> body) {
        Long userId = requireUser(); Map<String, Object> run = lockRun(userId, runId);
        String status = text(run.get("status"));
        if (!Set.of("CHOICE", "REWARD").contains(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前不是选择阶段");
        Long choiceId = number(body == null ? null : body.get("choiceId")); if (choiceId == null) throw new IllegalArgumentException("请选择一个选项");
        int level = intValue(run.get("current_level"), 1); Map<String, Object> choice;
        try { choice = jdbcTemplate.queryForMap("SELECT * FROM fc_card_rogue_choice WHERE id = ? AND run_id = ? AND level_no = ? AND selected = 0 FOR UPDATE", choiceId, runId, level); }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "选项已失效，请重新读取当前远征"); }
        String choiceType = text(choice.get("choice_type")); Map<String, Object> snapshot = parseSnapshot(text(choice.get("snapshot_json")));
        if ("ROLE".equals(choiceType)) {
            Long catalogId = number(choice.get("catalog_id"));
            Integer already = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fc_card_rogue_roster WHERE run_id = ? AND catalog_id = ?", Integer.class, runId, catalogId);
            if (already != null && already > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "本局已经拥有这张角色卡");
            jdbcTemplate.update("INSERT INTO fc_card_rogue_roster (run_id, catalog_id, source, snapshot_json) VALUES (?, ?, 'RECRUITED', ?)", runId, catalogId, choice.get("snapshot_json"));
        } else if ("BOOST".equals(choiceType)) {
            appendBoost(run, snapshot);
        } else throw new IllegalArgumentException("未知的远征选项");
        jdbcTemplate.update("UPDATE fc_card_rogue_choice SET selected = 1 WHERE id = ? AND run_id = ?", choiceId, runId);
        Map<String, Object> map = parseMap(text(run.get("map_json")));
        completeCurrentNode(map, text(run.get("current_node_id")));
        jdbcTemplate.update("UPDATE fc_card_rogue_run SET status = 'MAP', current_node_id = NULL, node_type = NULL, choice_context = NULL, encounter_json = NULL, event_json = NULL, map_json = ?, run_version = run_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?", json(map), runId, userId);
        return ApiResponse.ok(stateForRun(userId, runId));
    }

    @PostMapping("/runs/{runId}/event")
    @Transactional
    public ApiResponse<Map<String, Object>> resolveEvent(@PathVariable Long runId, @RequestBody Map<String, Object> body) {
        Long userId = requireUser(); Map<String, Object> run = lockRun(userId, runId);
        if (!"EVENT".equals(text(run.get("status")))) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前不是事件阶段");
        String optionKey = text(body == null ? null : body.get("optionKey")); Map<String, Object> event = parseSnapshot(text(run.get("event_json")));
        Map<String, Object> selected = readList(event.get("options")).stream().filter(item -> optionKey.equals(text(item.get("key")))).findFirst().orElse(null);
        if (selected == null) throw new IllegalArgumentException("事件选项无效");
        int reward = intValue(selected.get("points"), 0); int morale = intValue(run.get("morale"), 0) + intValue(selected.get("morale"), 0);
        if (selected.get("boost") instanceof Map<?, ?> boost) appendBoost(run, toStringMap(boost));
        Map<String, Object> map = parseMap(text(run.get("map_json"))); completeCurrentNode(map, text(run.get("current_node_id")));
        jdbcTemplate.update("UPDATE fc_card_rogue_run SET status = 'MAP', current_node_id = NULL, node_type = NULL, event_json = NULL, map_json = ?, morale = ?, reward_points = reward_points + ?, run_version = run_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?", json(map), morale, reward, runId, userId);
        return ApiResponse.ok(stateForRun(userId, runId));
    }

    @PostMapping("/runs/{runId}/battle")
    @Transactional
    public ApiResponse<Map<String, Object>> battle(@PathVariable Long runId, @RequestBody(required = false) Map<String, Object> body) {
        Long userId = requireUser(); Map<String, Object> run = lockRun(userId, runId);
        if (!"BATTLE".equals(text(run.get("status")))) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前不是战斗阶段");
        List<Long> rosterIds = readIds(body == null ? null : body.get("rosterIds"));
        List<Map<String, Object>> roster = jdbcTemplate.queryForList("SELECT id, snapshot_json AS snapshotJson, source FROM fc_card_rogue_roster WHERE run_id = ? ORDER BY id ASC", runId);
        if (rosterIds.isEmpty()) rosterIds = roster.stream().map(row -> number(row.get("id"))).filter(Objects::nonNull).limit(MAX_BATTLE_CARDS).toList();
        if (rosterIds.isEmpty() || rosterIds.size() > MAX_BATTLE_CARDS) throw new IllegalArgumentException("请选择 1～5 张角色卡出战");
        Set<Long> allowed = roster.stream().map(row -> number(row.get("id"))).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!allowed.containsAll(rosterIds) || new HashSet<>(rosterIds).size() != rosterIds.size()) throw new IllegalArgumentException("出战角色必须来自本局卡组");
        String tactic = text(body == null ? null : body.get("tactic")).toUpperCase(Locale.ROOT); if (!TACTICS.contains(tactic)) tactic = "DIRECT";
        Map<String, Object> map = parseMap(text(run.get("map_json"))); Map<String, Object> node = nodeById(map, text(run.get("current_node_id"))); if (node == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前节点信息已失效");
        Map<String, Integer> tagCounts = new HashMap<>(); int sum = 0;
        for (Long rosterId : rosterIds) {
            Map<String, Object> row = roster.stream().filter(item -> Objects.equals(number(item.get("id")), rosterId)).findFirst().orElseThrow(); Map<String, Object> card = parseSnapshot(text(row.get("snapshotJson")));
            sum += intValue(card.get("overall"), 60); for (String tag : readStringList(card.get("tags"))) tagCounts.merge(tag, 1, Integer::sum);
        }
        int average = Math.round(sum / (float) rosterIds.size()); int synergy = Math.min(10, tagCounts.values().stream().mapToInt(count -> Math.max(0, count - 1)).sum());
        String style = text(node.get("style")); int tacticBonus = tacticBonus(tactic, style); int boostBonus = boostBonus(run, tactic); int moraleBonus = intValue(run.get("morale"), 0) * 2;
        int level = intValue(node.get("level"), 1); int difficulty = intValue(node.get("difficulty"), 58) + Math.max(0, intValue(run.get("morale"), 0) * -1); int roll = Math.floorMod((int) (longValue(run.get("seed")) ^ text(node.get("id")).hashCode() ^ (level * 7919L)), 11);
        boolean win = average + synergy + tacticBonus + boostBonus + moraleBonus + roll >= difficulty;
        int cleared = win ? Math.max(intValue(run.get("cleared_levels"), 0), level) : intValue(run.get("cleared_levels"), 0);
        boolean boss = "BOSS".equals(text(node.get("type"))); int reward = Math.max(intValue(run.get("reward_points"), 0), cleared * 6) + (win && boss ? 30 : 0);
        Map<String, Object> battle = new LinkedHashMap<>(); battle.put("win", win); battle.put("tactic", tactic); battle.put("style", style); battle.put("power", average + synergy + tacticBonus + boostBonus + moraleBonus); battle.put("average", average); battle.put("synergy", synergy); battle.put("tacticBonus", tacticBonus); battle.put("boostBonus", boostBonus); battle.put("moraleBonus", moraleBonus); battle.put("roll", roll); battle.put("difficulty", difficulty); battle.put("rewardPreview", reward); battle.put("reason", win ? "阵容战力达到本节点要求" : "对手强度超过当前阵容，尝试更换战术或强化卡组");
        String nextStatus = !win ? "DEFEAT" : boss ? "VICTORY" : "REWARD";
        if (win && !boss) generateRewardChoices(runId, level, longValue(run.get("seed")), rosterCatalogIds(runId), userId);
        jdbcTemplate.update("UPDATE fc_card_rogue_run SET status = ?, cleared_levels = ?, reward_points = ?, last_battle_json = ?, run_version = run_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?", nextStatus, cleared, reward, json(battle), runId, userId);
        Map<String, Object> result = stateForRun(userId, runId); result.put("battle", battle); return ApiResponse.ok(result);
    }

    @PostMapping("/runs/{runId}/claim")
    @Transactional
    public ApiResponse<Map<String, Object>> claim(@PathVariable Long runId) {
        Long userId = requireUser(); Map<String, Object> run = lockRun(userId, runId); String status = text(run.get("status"));
        if (!Set.of("VICTORY", "DEFEAT", "CLAIMED").contains(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "完成一局远征后才能领取奖励");
        int reward = intValue(run.get("reward_points"), 0); int earned = 0;
        if (!"CLAIMED".equals(status)) {
            ensureWallet(userId); Map<String, Object> wallet = jdbcTemplate.queryForMap("SELECT balance FROM fc_user_points_wallet WHERE user_id = ? FOR UPDATE", userId); int balance = intValue(wallet.get("balance"), 0);
            int inserted = jdbcTemplate.update("INSERT IGNORE INTO fc_user_points_ledger (user_id, event_type, event_key, amount, balance_after, reference_id, description) VALUES (?, 'ROGUE', ?, ?, ?, ?, ?)", userId, "rogue:" + runId, reward, balance + reward, runId, "幻想远征 · 通过 " + intValue(run.get("cleared_levels"), 0) + " 关");
            if (inserted > 0) { jdbcTemplate.update("UPDATE fc_user_points_wallet SET balance = balance + ?, total_earned = total_earned + ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?", reward, reward, userId); earned = reward; }
            jdbcTemplate.update("UPDATE fc_card_rogue_run SET status = 'CLAIMED', claimed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?", runId, userId);
        }
        Map<String, Object> result = stateForRun(userId, runId); result.put("pointsEarned", earned); result.put("points", pointsSummary(userId)); return ApiResponse.ok(result);
    }

    @PostMapping("/runs/{runId}/abandon")
    @Transactional
    public ApiResponse<Map<String, Object>> abandon(@PathVariable Long runId) {
        Long userId = requireUser(); lockRun(userId, runId); jdbcTemplate.update("UPDATE fc_card_rogue_run SET status = 'ABANDONED', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ? AND status IN ('MAP','EVENT','CHOICE','BATTLE','REWARD')", runId, userId); return ApiResponse.ok(stateForUser(userId));
    }

    private Map<String, Object> stateForUser(Long userId) {
        List<Map<String, Object>> runs = jdbcTemplate.queryForList("SELECT id FROM fc_card_rogue_run WHERE user_id = ? AND status <> 'ABANDONED' ORDER BY updated_at DESC, id DESC LIMIT 1", userId);
        if (runs.isEmpty()) { Map<String, Object> empty = new LinkedHashMap<>(); empty.put("run", null); empty.put("roster", List.of()); empty.put("choices", List.of()); empty.put("map", emptyMap()); empty.put("history", historyFor(userId)); return empty; }
        return stateForRun(userId, number(runs.get(0).get("id")));
    }

    private Map<String, Object> stateForRun(Long userId, Long runId) {
        Map<String, Object> run = jdbcTemplate.queryForMap("SELECT id, status, current_level AS currentLevel, cleared_levels AS clearedLevels, max_level AS maxLevel, reward_points AS rewardPoints, claimed_at AS claimedAt, created_at AS createdAt, updated_at AS updatedAt, current_node_id AS currentNodeId, node_type AS nodeType, choice_context AS choiceContext, map_json AS mapJson, encounter_json AS encounterJson, event_json AS eventJson, boosts_json AS boostsJson, last_battle_json AS lastBattleJson, morale, run_version AS runVersion FROM fc_card_rogue_run WHERE id = ? AND user_id = ?", runId, userId);
        String mapRaw = text(run.remove("mapJson")); String encounterRaw = text(run.remove("encounterJson")); String eventRaw = text(run.remove("eventJson")); String boostsRaw = text(run.remove("boostsJson")); String battleRaw = text(run.remove("lastBattleJson"));
        List<Map<String, Object>> roster = jdbcTemplate.queryForList("SELECT id, user_card_id AS userCardId, catalog_id AS catalogId, source, snapshot_json AS snapshotJson FROM fc_card_rogue_roster WHERE run_id = ? ORDER BY id ASC", runId); roster.forEach(this::decodeSnapshot);
        int level = intValue(run.get("currentLevel"), 1); List<Map<String, Object>> choices = jdbcTemplate.queryForList("SELECT id, level_no AS level, choice_no AS choiceNo, catalog_id AS catalogId, choice_type AS choiceType, snapshot_json AS snapshotJson FROM fc_card_rogue_choice WHERE run_id = ? AND level_no = ? AND selected = 0 ORDER BY choice_no ASC", runId, level); choices.forEach(this::decodeChoice);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("run", run); result.put("roster", roster); result.put("choices", choices); result.put("map", mapRaw.isBlank() ? emptyMap() : parseMap(mapRaw)); result.put("encounter", encounterRaw.isBlank() ? null : parseSnapshot(encounterRaw)); result.put("event", eventRaw.isBlank() ? null : parseSnapshot(eventRaw)); result.put("boosts", boostsRaw.isBlank() ? List.of() : parseList(boostsRaw)); result.put("lastBattle", battleRaw.isBlank() ? null : parseSnapshot(battleRaw)); result.put("history", historyFor(userId)); return result;
    }

    private Map<String, Object> createMap(long seed) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int level = 1; level <= 7; level++) {
            for (int branch = 0; branch < 2; branch++) {
                String id = "L" + level + "B" + branch; String type = nodeType(level, branch); Map<String, Object> node = new LinkedHashMap<>(); node.put("id", id); node.put("level", level); node.put("branch", branch); node.put("type", type); node.put("title", nodeTitle(type, level)); node.put("description", nodeDescription(type)); node.put("style", encounterStyle(level, branch)); node.put("difficulty", 56 + level * 5 + branch * 3); node.put("status", level == 1 ? "AVAILABLE" : "LOCKED"); node.put("next", level == 7 ? List.of("BOSS") : List.of("L" + (level + 1) + "B0", "L" + (level + 1) + "B1")); nodes.add(node);
            }
        }
        Map<String, Object> boss = new LinkedHashMap<>(); boss.put("id", "BOSS"); boss.put("level", 8); boss.put("branch", 0); boss.put("type", "BOSS"); boss.put("title", "终局对决"); boss.put("description", "击败本局宿敌，完成幻想远征。"); boss.put("style", "CONTROL"); boss.put("difficulty", 82); boss.put("status", "LOCKED"); boss.put("next", List.of()); nodes.add(boss);
        Map<String, Object> map = new LinkedHashMap<>(); map.put("seedHint", Long.toHexString(seed).substring(0, 8)); map.put("maxLevel", MAX_LEVEL); map.put("currentNodeId", null); map.put("nodes", nodes); return map;
    }

    private String nodeType(int level, int branch) { if (level == 1) return branch == 0 ? "BATTLE" : "RECRUIT"; if (level == 2) return branch == 0 ? "REST" : "EVENT"; if (level == 3) return branch == 0 ? "ELITE" : "BATTLE"; if (level == 4) return branch == 0 ? "RECRUIT" : "REST"; if (level == 5) return branch == 0 ? "EVENT" : "ELITE"; if (level == 6) return branch == 0 ? "BATTLE" : "RECRUIT"; return branch == 0 ? "ELITE" : "EVENT"; }
    private String nodeTitle(String type, int level) { return switch (type) { case "RECRUIT" -> "招募营地"; case "REST" -> "休整营地"; case "EVENT" -> "未知事件"; case "ELITE" -> "精英对决"; default -> "第 " + level + " 关比赛"; }; }
    private String nodeDescription(String type) { return switch (type) { case "RECRUIT" -> "从三张新角色卡中招募一名伙伴。"; case "REST" -> "恢复士气，或换取一项局内强化。"; case "EVENT" -> "做出选择，承担风险换取额外收益。"; case "ELITE" -> "对手更强，但奖励也更丰厚。"; default -> "选择战术，带领临时卡组赢下比赛。"; }; }
    private String encounterStyle(int level, int branch) { return List.of("PRESS", "CONTROL", "COUNTER", "DIRECT").get(Math.floorMod(level + branch, 4)); }
    private String statusForNode(String type) { return Set.of("BATTLE", "ELITE", "BOSS").contains(type) ? "BATTLE" : Set.of("REST", "EVENT").contains(type) ? "EVENT" : "CHOICE"; }

    private Map<String, Object> encounterFor(Map<String, Object> node, Map<String, Object> run) { Map<String, Object> result = new LinkedHashMap<>(); result.put("title", text(node.get("title"))); result.put("description", text(node.get("description"))); result.put("style", text(node.get("style"))); result.put("difficulty", intValue(node.get("difficulty"), 60)); result.put("type", text(node.get("type"))); result.put("opponent", "幻想学院 · " + ("BOSS".equals(text(node.get("type"))) ? "冠军队" : text(node.get("style")) + "战术组")); return result; }
    private Map<String, Object> eventFor(Map<String, Object> node, Map<String, Object> run) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("title", text(node.get("title"))); result.put("description", text(node.get("description")));
        Map<String, Object> first = new LinkedHashMap<>(); Map<String, Object> second = new LinkedHashMap<>();
        if ("REST".equals(text(node.get("type")))) {
            first.put("key", "RECOVER"); first.put("label", "恢复士气"); first.put("detail", "士气 +2"); first.put("morale", 2);
            second.put("key", "PRACTICE"); second.put("label", "秘密训练"); second.put("detail", "获得一项 +4 战力强化"); second.put("boost", Map.of("boostKey", "POWER_PLUS", "value", 4, "name", "秘密训练", "description", "本局全队战力 +4"));
        } else {
            first.put("key", "SAFE"); first.put("label", "稳妥处理"); first.put("detail", "获得 8 点远征奖励"); first.put("points", 8);
            second.put("key", "RISK"); second.put("label", "接受挑战"); second.put("detail", "士气 +1，并获得一项 +7 战力强化"); second.put("morale", 1); second.put("boost", Map.of("boostKey", "POWER_PLUS", "value", 7, "name", "背水一战", "description", "本局全队战力 +7"));
        }
        result.put("options", List.of(first, second)); return result;
    }

    private void generateRoleChoices(Long runId, int level, long seed, Set<Long> excluded, Long userId) { generateChoices(runId, level, seed, excluded, userId, false); }
    private void generateRewardChoices(Long runId, int level, long seed, Set<Long> excluded, Long userId) { generateChoices(runId, level, seed, excluded, userId, true); }
    private void generateChoices(Long runId, int level, long seed, Set<Long> excluded, Long userId, boolean includeBoost) {
        jdbcTemplate.update("DELETE FROM fc_card_rogue_choice WHERE run_id = ? AND level_no = ?", runId, level);
        List<Map<String, Object>> catalog = jdbcTemplate.queryForList("SELECT id, name, description, position, photo_url AS photoUrl, source_url AS sourceUrl, overall, pace, shooting, passing, dribbling, defending, physical, tags_json AS tagsJson, skills_json AS skillsJson, traits_json AS traitsJson, price_points AS pricePoints FROM fc_persona_catalog WHERE status = 'PUBLISHED'"); catalog.removeIf(item -> excluded.contains(number(item.get("id"))));
        Set<Long> ownedCatalog = jdbcTemplate.queryForList("SELECT catalog_id FROM fc_persona_inventory WHERE user_id = ?", Long.class, userId).stream().filter(Objects::nonNull).collect(Collectors.toSet()); long mixed = seed ^ (level * 104729L);
        List<Map<String, Object>> fresh = catalog.stream().filter(item -> !ownedCatalog.contains(number(item.get("id")))).collect(Collectors.toCollection(ArrayList::new)); List<Map<String, Object>> fallback = catalog.stream().filter(item -> ownedCatalog.contains(number(item.get("id")))).collect(Collectors.toCollection(ArrayList::new)); Collections.shuffle(fresh, new Random(mixed)); Collections.shuffle(fallback, new Random(mixed ^ 0x9E3779B97F4A7C15L)); List<Map<String, Object>> candidates = new ArrayList<>(); candidates.addAll(fresh); candidates.addAll(fallback);
        int roleCount = includeBoost ? Math.min(2, candidates.size()) : Math.min(MIN_CHOICE_COUNT, candidates.size()); for (int i = 0; i < roleCount; i++) insertChoice(runId, level, i + 1, "ROLE", number(candidates.get(i).get("id")), normalizeSnapshot(candidates.get(i)));
        if (includeBoost) { List<Map<String, Object>> boosts = boostPool(); for (int choiceNo = roleCount + 1; choiceNo <= MIN_CHOICE_COUNT; choiceNo++) insertChoice(runId, level, choiceNo, "BOOST", 0L, boosts.get(Math.floorMod((int) (mixed ^ (choiceNo * 31L)), boosts.size()))); }
        if (!includeBoost && roleCount < MIN_CHOICE_COUNT) { List<Map<String, Object>> boosts = boostPool(); for (int i = roleCount; i < MIN_CHOICE_COUNT; i++) insertChoice(runId, level, i + 1, "BOOST", 0L, boosts.get((i + level) % boosts.size())); }
    }

    private void insertChoice(Long runId, int level, int choiceNo, String type, Long catalogId, Map<String, Object> snapshot) { jdbcTemplate.update("INSERT INTO fc_card_rogue_choice (run_id, level_no, choice_no, catalog_id, choice_type, snapshot_json) VALUES (?, ?, ?, ?, ?, ?)", runId, level, choiceNo, catalogId == null ? 0L : catalogId, type, json(snapshot)); }
    private List<Map<String, Object>> boostPool() { return List.of(Map.of("boostKey", "POWER_PLUS", "value", 5, "name", "临场爆发", "description", "本局全队战力 +5"), Map.of("boostKey", "TACTIC_PRESS", "value", 6, "name", "高压训练", "description", "使用高压战术时额外 +6"), Map.of("boostKey", "TACTIC_CONTROL", "value", 6, "name", "控球训练", "description", "使用控球战术时额外 +6"), Map.of("boostKey", "MORALE", "value", 1, "name", "队魂", "description", "士气 +1")); }

    private void completeCurrentNode(Map<String, Object> map, String currentId) { Map<String, Object> current = nodeById(map, currentId); if (current == null) return; current.put("status", "CLEARED"); for (Map<String, Object> node : nodes(map)) if ("AVAILABLE".equals(text(node.get("status"))) && intValue(node.get("level"), 0) == intValue(current.get("level"), 0)) node.put("status", "SKIPPED"); for (Object next : asList(current.get("next"))) { Map<String, Object> target = nodeById(map, text(next)); if (target != null && !"CLEARED".equals(text(target.get("status")))) target.put("status", "AVAILABLE"); } map.put("currentNodeId", null); }
    private void appendBoost(Map<String, Object> run, Map<String, Object> boost) { List<Map<String, Object>> boosts = parseList(text(run.get("boosts_json"))); boosts.add(new LinkedHashMap<>(boost)); jdbcTemplate.update("UPDATE fc_card_rogue_run SET boosts_json = ?, morale = morale + ? WHERE id = ? AND user_id = ?", json(boosts), "MORALE".equals(text(boost.get("boostKey"))) ? intValue(boost.get("value"), 0) : 0, run.get("id"), UserContext.getUserId()); }
    private int tacticBonus(String tactic, String style) { if (tactic.equals(style)) return 8; if ("DIRECT".equals(tactic)) return 3; return 0; }
    private int boostBonus(Map<String, Object> run, String tactic) { int total = 0; for (Map<String, Object> boost : parseList(text(run.get("boosts_json")))) { String key = text(boost.get("boostKey")); if ("POWER_PLUS".equals(key)) total += intValue(boost.get("value"), 0); if (("TACTIC_" + tactic).equals(key)) total += intValue(boost.get("value"), 0); } return Math.min(25, total); }
    private Set<Long> rosterCatalogIds(Long runId) { return jdbcTemplate.queryForList("SELECT catalog_id FROM fc_card_rogue_roster WHERE run_id = ?", Long.class, runId).stream().filter(Objects::nonNull).collect(Collectors.toSet()); }
    private Map<String, Object> nodeById(Map<String, Object> map, String id) { return nodes(map).stream().filter(node -> id.equals(text(node.get("id")))).findFirst().orElse(null); }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodes(Map<String, Object> map) { Object value = map.get("nodes"); if (!(value instanceof List<?> list)) return new ArrayList<>(); List<Map<String, Object>> result = new ArrayList<>(); for (Object item : list) if (item instanceof Map<?, ?>) result.add((Map<String, Object>) item); return result; }
    private Map<String, Object> parseMap(String raw) { Map<String, Object> map = parseSnapshot(raw); if (!map.containsKey("nodes")) return emptyMap(); return map; }
    private Map<String, Object> emptyMap() { Map<String, Object> map = new LinkedHashMap<>(); map.put("maxLevel", MAX_LEVEL); map.put("currentNodeId", null); map.put("nodes", List.of()); return map; }
    private Map<String, Object> eventForNode(String raw) { return parseSnapshot(raw); }
    private Map<String, Object> parseSnapshot(String raw) { try { return objectMapper.readValue(raw, new TypeReference<>() {}); } catch (Exception ignored) { return new LinkedHashMap<>(); } }
    private List<Map<String, Object>> parseList(String raw) { try { return objectMapper.readValue(raw, new TypeReference<>() {}); } catch (Exception ignored) { return new ArrayList<>(); } }
    private void decodeSnapshot(Map<String, Object> row) { String raw = text(row.remove("snapshotJson")); row.put("card", parseSnapshot(raw)); }
    private void decodeChoice(Map<String, Object> row) { String raw = text(row.remove("snapshotJson")); if ("BOOST".equals(text(row.get("choiceType")))) row.put("boost", parseSnapshot(raw)); else row.put("card", parseSnapshot(raw)); }
    private Map<String, Object> normalizeSnapshot(Map<String, Object> row) { Map<String, Object> result = new LinkedHashMap<>(); result.put("catalogId", number(row.getOrDefault("catalogId", row.get("id")))); result.put("name", text(row.getOrDefault("name", row.get("player_name")))); result.put("description", text(row.getOrDefault("description", row.get("bio_summary")))); result.put("position", text(row.get("position"))); result.put("photoUrl", text(row.getOrDefault("photoUrl", row.get("photo_url")))); result.put("sourceUrl", text(row.getOrDefault("sourceUrl", row.get("source_url")))); for (String key : List.of("overall", "pace", "shooting", "passing", "dribbling", "defending", "physical", "pricePoints")) result.put(key, intValue(row.getOrDefault(key, row.get(snake(key))), 60)); result.put("tags", readStringList(row.getOrDefault("tags", row.get("tagsJson")))); result.put("skills", readStringList(row.getOrDefault("skills", row.get("skillsJson")))); result.put("traits", readStringList(row.getOrDefault("traits", row.get("traitsJson")))); return result; }
    private List<Long> readIds(Object value) { if (!(value instanceof Collection<?> collection)) return new ArrayList<>(); return collection.stream().map(CardRogueController::number).filter(Objects::nonNull).distinct().limit(MAX_BATTLE_CARDS).collect(Collectors.toCollection(ArrayList::new)); }
    private List<String> readStringList(Object value) { if (value instanceof Collection<?> collection) return collection.stream().map(CardRogueController::text).filter(item -> !item.isBlank()).limit(16).toList(); String raw = text(value); if (raw.isBlank()) return List.of(); try { return objectMapper.readValue(raw, new TypeReference<List<String>>() {}); } catch (Exception ignored) { return Arrays.stream(raw.split(",")).map(String::trim).filter(item -> !item.isBlank()).limit(16).toList(); } }
    private List<Map<String, Object>> readList(Object value) { if (!(value instanceof Collection<?> collection)) return List.of(); return collection.stream().filter(item -> item instanceof Map<?, ?>).map(item -> toStringMap((Map<?, ?>) item)).toList(); }
    private List<Object> asList(Object value) { return value instanceof Collection<?> collection ? new ArrayList<>(collection) : List.of(); }
    private Map<String, Object> toStringMap(Map<?, ?> source) { Map<String, Object> out = new LinkedHashMap<>(); source.forEach((key, value) -> out.put(String.valueOf(key), value)); return out; }
    private List<Map<String, Object>> historyFor(Long userId) { return jdbcTemplate.queryForList("SELECT id, status, cleared_levels AS clearedLevels, max_level AS maxLevel, reward_points AS rewardPoints, created_at AS createdAt, claimed_at AS claimedAt FROM fc_card_rogue_run WHERE user_id = ? AND status NOT IN ('ABANDONED','MAP','EVENT','CHOICE','BATTLE','REWARD') ORDER BY id DESC LIMIT 5", userId); }
    private Map<String, Object> pointsSummary(Long userId) { ensureWallet(userId); return new LinkedHashMap<>(jdbcTemplate.queryForMap("SELECT balance, total_earned AS totalEarned, total_spent AS totalSpent FROM fc_user_points_wallet WHERE user_id = ?", userId)); }
    private void ensureWallet(Long userId) { jdbcTemplate.update("INSERT IGNORE INTO fc_user_points_wallet (user_id, balance, total_earned, total_spent) VALUES (?, 0, 0, 0)", userId); }
    private Map<String, Object> lockRun(Long userId, Long runId) { try { return jdbcTemplate.queryForMap("SELECT * FROM fc_card_rogue_run WHERE id = ? AND user_id = ? FOR UPDATE", runId, userId); } catch (Exception error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "远征不存在或不属于当前用户"); } }
    private void addColumnIfMissing(String table, String column, String definition) { try { jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition); } catch (Exception ignored) { } }
    private Long requireUser() { Long id = UserContext.getUserId(); if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"); return id; }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception error) { return "{}"; } }
    private static String snake(String key) { return switch (key) { case "photoUrl" -> "photo_url"; case "sourceUrl" -> "source_url"; case "pricePoints" -> "price_points"; default -> key; }; }
    private static Long number(Object value) { try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (Exception ignored) { return null; } }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static int intValue(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; } }
    private static long longValue(Object value) { try { return value == null ? 0L : Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return 0L; } }
}
