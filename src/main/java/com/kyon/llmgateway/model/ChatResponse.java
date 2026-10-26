package com.kyon.llmgateway.model;

import lombok.Builder;
import lombok.Data;
import tools.jackson.databind.JsonNode;

/**
 * {model, content, latencyMs, tokens, cost}
 */
@Data
@Builder
public class ChatResponse {
    // AI 返回的文本内容
    private String content;

    // 模型的思考过程（上游 message.reasoning / reasoning_content）,无推理模型为null
    private String reasoningContent;

    // 实际使用的模型名
    private String model;

    // 输入 Token 数
    private Integer inputTokens;

    // 输出 Token 数
    private Integer outputTokens;

    // token 数是否为估算值：true 表示上游未返回 usage，由 TokenCounter 估算
    private Boolean estimated;

    // 请求耗时（毫秒）
    private Long latency;

    // "stop" 标识正常结束， "tool_call" 标识需要调用工具
    private String finishReason;

    // LLM 返回的 tool_calls 数组，null 表示不需要调工具
    private JsonNode toolCalls;

    /**
     * 判断是否有工具调用
     */
    public boolean hasToolCalls() {
        return toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty();
    }
}
