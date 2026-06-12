package com.kyon.llmgateway.agent;

import lombok.Data;

@Data
public class ToolCallRecord {
    /**
     * 工具调用信息
     */
    private ToolCall toolCall;

    /**
     * 工具返回结果
     */
    private String result;

    public ToolCallRecord(ToolCall toolCall, String result) {
        this.toolCall = toolCall;
        this.result = result;
    }
}
