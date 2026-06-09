package com.kyon.llmgateway.controller;

import com.kyon.llmgateway.agent.engine.ContextManager;
import com.kyon.llmgateway.agent.engine.ToolEngine;
import com.kyon.llmgateway.agent.engine.ToolResult;
import com.kyon.llmgateway.agent.tool.ToolRegistry;
import com.kyon.llmgateway.model.*;
import com.kyon.llmgateway.service.LLMService;
import com.kyon.llmgateway.service.LLMServiceFactory;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

/**
 * POST /api/chat - 统一入口
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Resource
    private LLMServiceFactory factory;
    @Resource
    private ToolRegistry toolRegistry;
    @Resource
    private ToolEngine toolEngine;
    @Resource
    private ContextManager contextManager;

    /**
     * 普通交互(Loop)
     * @param request 请求体
     * @return ChatResponse
     */
    @PostMapping("/completions")
    public ApiResult<ChatResponse> chat(@RequestBody ChatRequest request) {
        try {
            LLMService service = factory.getLlmService(request.getModel());

            // Loop 执行，构建 messages 列表
            List<Message> messages = new ArrayList<>(request.getMessages());

            // 组装 tools 定义 (从 ToolRegistry 拿所有注册的工具)
            List<ToolDefinition> tools = toolRegistry.getAllTools().stream()
                    .map(t -> new ToolDefinition("function",
                            new ToolDefinition.FunctionDef(
                                    t.getName(),
                                    t.getDescription(),
                                    t.getParameters()
                            )
                    )).toList();

            // 设置默认值, 最大调用次数(10次), 当前调用次数(初始化为0)
            int maxToolCalls = 10;
            int callCount = 0;

            while (callCount < maxToolCalls) {
                // 0. 裁剪上下文，防止超限
                messages = contextManager.truncate(messages);
                messages = contextManager.truncateByTokens(messages);
                // 1. 调用 LLM
                ChatResponse response = service.chat(messages, tools);

                log.info("Loop {} finishReason={}, toolCalls={}, content={}",
                        callCount, response.getFinishReason(), response.getToolCalls(), response.getContent());
                // 2. 没有 tool_calls -> 返回最终回复
                if (!response.hasToolCalls()) {
                    return ApiResult.success(response);
                }

                // 3. 有 tool_calls -> 把 assistant 的 tool_calls 消息追加到历史
                Message assistantMsg = new Message("assistant", response.getContent(), response.getToolCalls(),
                        null, null);
                messages.add(assistantMsg);

                // 4. 执行工具
                List<ToolResult> results = toolEngine.execute(response.getToolCalls());

                // 5. 把工具结果追加到历史
                for (ToolResult r : results) {
                    Message toolMsg = new Message("tool", r.getContent(), null, r.getToolCallId(), r.getToolName());
                    messages.add(toolMsg);
                }
                callCount++;
            }

            // 超过最大调用次数
            return ApiResult.success(ChatResponse.builder()
                    .content("已达到最大工具调用次数限制")
                    .model(request.getModel())
                    .build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 流式回复 SSE
     * @param request 请求体
     * @return SseEmitter 流式Emitter
     */
    @PostMapping("/completions/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        LLMService service = factory.getLlmService(request.getModel());
        return service.stream(request.getMessages());
    }
}
