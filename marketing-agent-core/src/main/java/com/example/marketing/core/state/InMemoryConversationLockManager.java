package com.example.marketing.core.state;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

@Service
public class InMemoryConversationLockManager implements ConversationLockManager {
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T withConversationLock(String conversationId, Supplier<T> supplier) {
        ReentrantLock lock = locks.computeIfAbsent(conversationId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return supplier.get();
        }
        finally {
            lock.unlock();
        }
    }
}
