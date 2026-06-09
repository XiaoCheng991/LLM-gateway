package com.kyon.llmgateway.agent.engine;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ToolResult {

    // 对应 LLM 返回的 tool_call_id，回传时要用到
    private String toolCallId;
    // 工具名
    private String toolName;
    // 执行结果文本
    private String content;
}
