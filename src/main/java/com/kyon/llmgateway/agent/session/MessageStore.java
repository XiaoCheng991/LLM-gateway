package com.kyon.llmgateway.agent.session;

import com.kyon.llmgateway.model.Message;

import java.util.List;

public interface MessageStore {
    /**
     * 保存一条消息
     */
    void save(String sessionId, Message message);

    /**
     * 加载会话的所有信息，按时间正序
     */
    List<Message> load(String sessionId);

    /**
     * 删除会话的所有信息
     */
    void delete(String sessionId);
}
