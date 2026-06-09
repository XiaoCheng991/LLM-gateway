package com.kyon.llmgateway.agent.engine;

import com.kyon.llmgateway.model.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ContextManager {

    // 最大配置数，后期可配置
    private static final int MAX_TOKENS = 8000;
    private static final int MAX_MESSAGES = 20;

    /** 按消息数量裁剪，保留第一条 + 最近 N-1 条 */
    public List<Message> truncate(List<Message> messages) {
        if (messages.size() <= MAX_MESSAGES) return messages;

        List<Message> result = new ArrayList<>();
        result.add(messages.getFirst()); // 保留第一条
        result.addAll(messages.subList(messages.size() - (MAX_MESSAGES - 1), messages.size())); // 保留最近的 N-1 条
        return result;
    }

    /** 按 token 数裁剪, 从最早的非 system 消息开始移除 */
    public List<Message> truncateByTokens(List<Message> messages) {
        int total = TokenCounter.estimateMessages(messages);
        if (total <= MAX_TOKENS) return messages;

        List<Message> result = new ArrayList<>(messages);
        while (TokenCounter.estimateMessages(result) > MAX_TOKENS && result.size() > 1) {
            result.remove(1);
        }
        return result;
    }
}
