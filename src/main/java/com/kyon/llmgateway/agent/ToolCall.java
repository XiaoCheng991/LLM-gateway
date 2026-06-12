package com.kyon.llmgateway.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {
    /**
     * 工具调用 ID，和 LLM 返回的 tool_call.id 对应
     */
    private String id;

    /**
     * 工具名，如 calculator
     */
    private String name;

    /**
     * 工具参数，结构因工具而异，用 JsonNode 保持灵活
     */
    private JsonNode arguments;

}
