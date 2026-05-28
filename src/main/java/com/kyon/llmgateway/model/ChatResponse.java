package com.kyon.llmgateway.model;

import lombok.Builder;
import lombok.Data;

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
}
