package com.kyon.llmgateway.agent.tool;

import tools.jackson.databind.JsonNode;

/**
 * 工具接口 - 所有 Agent 可调用的工具都要实现此接口
 */
public interface Tool {
    /** 工具名称，唯一标识。如 "calculator"、"get_weather" */
    String getName();

    /** 工具描述，LLM 根据这个描述决定是否调用，越清晰越好 */
    String getDescription();

    /** 工具参数，定义了工具所需的输入参数 */
    JsonNode getParameters();

    /** 执行工具逻辑，arguments 是LLM传递的参数，返回执行结果文本 */
    String execute(JsonNode arguments);
}
