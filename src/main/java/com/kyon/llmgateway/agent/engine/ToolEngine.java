package com.kyon.llmgateway.agent.engine;

import com.kyon.llmgateway.agent.tool.Tool;
import com.kyon.llmgateway.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Component
public class ToolEngine {

    private static final Logger log = LoggerFactory.getLogger(ToolEngine.class);
    private static final ObjectMapper om = new ObjectMapper();

    // 参数构造注入
    private final ToolRegistry toolRegistry;

    public ToolEngine(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 解析 LLM 返回的 tool_calls，逐个执行，收集结果
     * @param toolCalls LLM 返回的 tool_calls 数组
     * @return 工具执行结果列表
     */
    public List<ToolResult> execute(JsonNode toolCalls) {
        List<ToolResult> results = new ArrayList<>();

        for (JsonNode tc : toolCalls) {
            if (tc == null) {
                continue;
            }

            String callId = tc.path("id").asString("");
            JsonNode functionNode = tc.path("function");
            if (callId.isEmpty() || functionNode.isNull()) {
                log.warn("Invalid tool_call, skipping: {}", tc);
                continue;
            }

            String name = functionNode.path("name").asString("");
            String argStr = functionNode.path("arguments").isNull() ?
                    "{}" : functionNode.path("arguments").toString();

            log.info("Executing tool: {}, callId: {}, args: {}", name, callId, argStr);

            try {
                // 1.从注册表拿工具
                Tool tool = toolRegistry.getTool(name);
                if (tool == null) {
                    throw new RuntimeException("工具未找到: " + name);
                }

                // 2. 解析参数
                JsonNode args = om.readTree(argStr);
                // 3. 执行工具
                String result = tool.execute(args);
                // 4. 收集结果
                results.add(new ToolResult(callId, name, result));
                log.info("Executing Tool {} result: {}", name, result);
            } catch (Exception e) {
                log.error("Tool execution failed: {}", name, e);
                results.add(new ToolResult(callId, name, "执行出错: " + e.getMessage()));
            }
        }
        return results;
    }
}
