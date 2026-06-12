package com.kyon.llmgateway.controller;

import com.kyon.llmgateway.agent.AgentRequest;
import com.kyon.llmgateway.agent.AgentResponse;
import com.kyon.llmgateway.agent.impl.SimpleAgent;
import com.kyon.llmgateway.agent.tool.ToolRegistry;
import com.kyon.llmgateway.model.*;
import com.kyon.llmgateway.service.LLMService;
import com.kyon.llmgateway.service.LLMServiceFactory;
import jakarta.annotation.Resource;
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

    @Resource
    private LLMServiceFactory factory;
    @Resource
    private SimpleAgent simpleAgent;

    /**
     * 普通交互(Loop)
     * @param request 请求体
     * @return ChatResponse
     */
    @PostMapping("/completions")
    public ApiResult<ChatResponse> chat(@RequestBody ChatRequest request) {
        try {
            // Loop 执行，构建 messages 列表
            List<Message> messages = new ArrayList<>(request.getMessages());

            // 取最后一条用户消息的 content
            String lastUserMessage = messages.stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .reduce((first, second) -> second)  // 取最后一条
                    .map(Message::getContent)
                    .orElse("");

            // 改为调 Agent的 Tool Loop
            AgentRequest agentRequest = new AgentRequest();
            agentRequest.setMessage(lastUserMessage);
            agentRequest.setModel(request.getModel());
            agentRequest.setSessionId("");  // 新会话
            AgentResponse response = simpleAgent.chat(agentRequest);

            // 超过最大调用次数
            return ApiResult.success(ChatResponse.builder()
                    .content(response.getContent())
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
