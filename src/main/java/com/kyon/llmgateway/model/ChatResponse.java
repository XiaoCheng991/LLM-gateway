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

    // 实际使用的模型名
    private String model;

    // 输入 Token 数
    private Integer inputTokens;

    // 输出 Token 数
    private Integer outputTokens;

    // 请求耗时（毫秒）
    private Long latency;

    // "stop" 标识正常结束， "tool_call" 标识需要调用工
    private String finishReason;

    // LLM 返回的 tool_calls 数组，null 表示不需要调工具
    private JsonNode toolCalls;

    /** 判断是否有工具调用 */
    public boolean hasToolCalls() {
        return toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty();
    }
}
