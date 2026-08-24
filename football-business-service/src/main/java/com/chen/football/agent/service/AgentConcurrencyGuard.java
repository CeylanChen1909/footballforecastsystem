package com.chen.football.agent.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Prevents a single account or a traffic burst from occupying every model worker. */
@Component
public class AgentConcurrencyGuard {
    private final Semaphore global = new Semaphore(8);
    private final ConcurrentHashMap<String, AtomicInteger> users = new ConcurrentHashMap<>();

    public boolean tryAcquire(Long userId) {
        String key = userId == null ? "anonymous" : "user:" + userId;
        AtomicInteger count = users.computeIfAbsent(key, ignored -> new AtomicInteger());
        if (count.incrementAndGet() > 2) {
            count.decrementAndGet();
            cleanup(key, count);
            return false;
        }
        if (!global.tryAcquire()) {
            count.decrementAndGet();
            cleanup(key, count);
            return false;
        }
        return true;
    }

    public void release(Long userId) {
        String key = userId == null ? "anonymous" : "user:" + userId;
        AtomicInteger count = users.get(key);
        if (count != null) {
            count.decrementAndGet();
            cleanup(key, count);
        }
        global.release();
    }

    private void cleanup(String key, AtomicInteger count) {
        if (count.get() <= 0) users.remove(key, count);
    }
}
