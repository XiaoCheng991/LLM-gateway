package com.kyon.llmgateway.agent.session;

import com.kyon.llmgateway.agent.engine.ContextManager;
import com.kyon.llmgateway.model.Message;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理
 * 给每个对话一个 “记忆本”，不同对话的记忆互不干扰
 */
@Component
public class SessionManager {
    // 对应 sessionId -> 消息列表
    private final Map<String, List<Message>> cache = new ConcurrentHashMap<>();

    @Resource
    private ContextManager contextManager;
    @Resource
    private MessageStore messageStore;

    /**
     * 获取或创建会话
     * 返回的是 原始的饮用，调用方可以 append() 追加消息
     */
    public List<Message> getOrCreate(String sessionId) {
        // 先查缓存
        List<Message> cached = cache.get(sessionId);
        if (cached != null) return cached;

        // 缓存 miss 未命中 -> 从 SQLite 加载
        List<Message> loaded = messageStore.load(sessionId);
        cache.put(sessionId, loaded);
        return new ArrayList<>(loaded);
    }

    /**
     * 追加消息到会话
     */
    public void append(String sessionId, Message message) {
        List<Message> messages = getOrCreate(sessionId);
        synchronized (messages) {
            messages.add(message);
        }

        // 同步写 SQLite
        messageStore.save(sessionId, message);
    }

    /**
     * 获取会话历史（自动裁剪上下文）
     * 返回裁剪后的副本，给 LLM 调用，不会污染原始数据
     */
    public List<Message> getHistory(String sessionId) {
        List<Message> messages = getOrCreate(sessionId);
        // 裁剪上下文, 需要接受返回值，返回的是一个新的 list
        messages = contextManager.truncate(messages);
        messages = contextManager.truncateByTokens(messages);
        return new ArrayList<>(messages);
    }

    /**
     * 清除会话
     */
    public void clear(String sessionId) {
        cache.remove(sessionId);
        // 同步删除 SQLite
        messageStore.delete(sessionId);
    }

    /**
     * 生成新会话 ID
     * 完整 UUID 太长（36字符），截 16 位够用，碰撞概率极低 (2^64 分之一)
     */
    public String newSessionId() {
        return UUID.randomUUID().toString()         // "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                .replace("-","")  // "a1b2c3d4e5f67890abcdef1234567890"
                .substring(0, 16);                  // "a1b2c3d4e5f67890"
    }
}
