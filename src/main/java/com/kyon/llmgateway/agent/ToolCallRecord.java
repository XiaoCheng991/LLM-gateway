package com.kyon.llmgateway.agent;

/**
 * @param toolCall 工具调用信息
 * @param result   工具返回结果
 */
public record ToolCallRecord(ToolCall toolCall, String result) {

}
