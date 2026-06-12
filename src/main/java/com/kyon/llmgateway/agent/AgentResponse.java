package com.kyon.llmgateway.agent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AgentResponse {
    /**
     * 最终回答内容
     */
    private String content;

    /**
     * 会话ID，前端后续请求带上
     */
    private String sessionId;

    /**
     * 工具调用历史
     */
    private List<ToolCallRecord> toolHistory;

    /**
     * 输入 token 数
     */
    private int inputTokens;

    /**
     * 输出 token 数
     */
    private int outputTokens;
}