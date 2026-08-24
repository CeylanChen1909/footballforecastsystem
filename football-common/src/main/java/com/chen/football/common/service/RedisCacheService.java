package com.chen.football.common.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);
    private static final String PREFIX = "api-football:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public <T> T get(String key, Class<T> clazz) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(PREFIX + key);
        } catch (RuntimeException ex) {
            log.debug("Redis cache read unavailable key={}: {}", key, ex.getMessage());
            return null;
        }
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.warn("Redis 缓存反序列化失败 key={} err={}", key, e.getMessage());
            return null;
        }
    }

    public void set(String key, Object value, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(PREFIX + key, json, Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException e) {
            log.warn("Redis 缓存序列化失败 key={} err={}", key, e.getMessage());
        } catch (RuntimeException e) {
            log.debug("Redis cache write unavailable key={}: {}", key, e.getMessage());
        }
    }

    public void evict(String key) {
        try {
            redisTemplate.delete(PREFIX + key);
        } catch (RuntimeException ex) {
            log.debug("Redis cache evict unavailable key={}: {}", key, ex.getMessage());
        }
    }

    public void evictPrefix(String prefix) {
        try {
            var keys = redisTemplate.keys(PREFIX + prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (RuntimeException ex) {
            log.debug("Redis cache prefix eviction unavailable prefix={}: {}", prefix, ex.getMessage());
        }
    }
}
