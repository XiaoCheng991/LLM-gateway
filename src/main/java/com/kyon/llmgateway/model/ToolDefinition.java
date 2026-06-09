package com.kyon.llmgateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import tools.jackson.databind.JsonNode;

@Data
@Builder
@AllArgsConstructor
public class ToolDefinition {
    // 固定 "function"
    private String type;
    private FunctionDef function;

    @Data
    @Builder
    @AllArgsConstructor
    public static class FunctionDef {
        // 工具名，匹配 Tool.getName()
        private String name;

        // 工具描述，匹配 Tool.getDescription()
        private String description;

        // Json Scheme，匹配 Tool.getParameters()
        private JsonNode parameters;
    }
}
