package com.kyon.llmgateway.agent.engine;

import com.kyon.llmgateway.model.ChatResponse;
import com.kyon.llmgateway.model.Message;
import com.kyon.llmgateway.service.LLMService;
import com.kyon.llmgateway.service.LLMServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 滑动窗口上下文管理
 */
@Component
public class ContextManager {
    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    // 最大配置数，后期可配置
    private static final int MAX_TOKENS = 8000;
    private static final int MAX_MESSAGES = 20;

    // 压缩配置
    private static final int DEFAULT_CONTEXT_WINDOW = 32000;
    private static final double SUMMARIZE_THRESHOLD = 0.7;

    /** 按消息数量裁剪，保留第一条 + 最近 N-1 条 */
    public List<Message> truncate(List<Message> messages) {
        if (messages.size() <= MAX_MESSAGES) return messages;

        List<Message> result = new ArrayList<>();
        result.add(messages.getFirst()); // 保留第一条
        result.addAll(messages.subList(messages.size() - (MAX_MESSAGES - 1), messages.size())); // 保留最近的 N-1 条
        return new ArrayList<>(result);
    }

    /** 按 token 数裁剪, 从最早的非 system 消息开始移除 */
    public List<Message> truncateByTokens(List<Message> messages) {
        int total = TokenCounter.estimateMessages(messages);
        if (total <= MAX_TOKENS) return messages;

        List<Message> result = new ArrayList<>(messages);

        // 优化时间复杂度，从最早的 非 system 消息开始移除，直到 token 数低于阈值
        int index = 1; // index = 0 时第一条是 system message，跳过
        while (total > MAX_TOKENS && result.size() > 1) {
           // 减去被移除消息的 token 数
            Message removed = result.remove(index);
            total -= TokenCounter.estimateTokens(removed.getContent()) + 5; // +5 是消息格式开销
        }
        return new ArrayList<>(result);
    }

    /**
     * 自动检测：token 超过 context window 70% 时出发摘要压缩
     */
    public List<Message> truncateToThreshold(List<Message> messages, LLMServiceFactory factory) throws Exception {
        int threshold = (int) (DEFAULT_CONTEXT_WINDOW * SUMMARIZE_THRESHOLD);
        if (TokenCounter.estimateMessages(messages) <= threshold) {
            return messages;
        }
        // 超出阈值，触发摘要（factory 为 null 时，由调用方保证传入）
        return summarize(messages, DEFAULT_CONTEXT_WINDOW, factory);
    }

    /**
     * 讲历史消息压缩为摘要，保留最近 1/3（最少 5 条）
     * @param messages 完整消息列表
     * @param contextWindowTokens context window 大小（预留参数，当前用默认值）
     * @param factory   LLMServiceFactory，用于获取 LLM 做摘要
     */
    public List<Message> summarize(List<Message> messages, int contextWindowTokens, LLMServiceFactory factory) throws Exception {
        if (messages == null || messages.isEmpty() || factory == null) {
            return messages;
        }

        // 保留最近 1/3（最少 5 条）
        int keepCount = Math.max(5, messages.size() / 3);
        if (messages.size() <= keepCount) {
            return messages;
        }

        List<Message> toSummarize = messages.subList(0, messages.size() - keepCount);

        // 保留 keepCount 长度的消息
        List<Message> toKeep = new ArrayList<>(messages.subList(messages.size() - keepCount, messages.size()));

        // 拼接历史消息为文本
        StringBuilder sb = new StringBuilder();
        for (Message m : toSummarize) {
            sb.append(m.getRole()).append(": ").append(m.getContent()).append("\n");
        }

        // 构建摘要请求
        List<Message> summaryInput = List.of(
                new Message("system", "Summarize the following conversation history concisely. Preserve key facts, " +
                        "decisions, tool call results, and user intent. Output a single paragraph summary.", null, null, null),
                new Message("user", sb.toString(), null, null, null)
        );

        // 调 LLM 生成摘要
        LLMService service = factory.getLlmService(null);
        if (service == null) {
            log.warn("LLMService 获取失败, 跳过摘要压缩");
            return messages;
        }
        ChatResponse response = service.chat(summaryInput, null);
        String summaryContent = response.getContent();

        // 构造摘要消息
        Message summaryMsg = new Message("system", "[Previous conversation summary: " + summaryContent + "]", null, null, null);

        // 摘要 + 保留的消息
        List<Message> result = new ArrayList<>();
        result.add(summaryMsg);
        result.addAll(toKeep);
        return result;
    }

}
