package com.kyon.llmgateway.eval.runner;

import com.kyon.llmgateway.eval.model.CaseOutput;
import com.kyon.llmgateway.eval.model.EvalCase;
import com.kyon.llmgateway.model.ChatResponse;
import com.kyon.llmgateway.model.Message;
import com.kyon.llmgateway.service.LLMService;
import com.kyon.llmgateway.service.LLMServiceFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * kind=llm, 裸问模型 -- 不带工具、不做 tool loop、不带会话历史
 * 等价性：与 /api/chat/completions 非流式路径等价（Controller 是纯转发，不注入 system、不改写messages。）
 */
@Component
public class LlmCaseExecutor implements CaseExecutor {

    @Resource
    private LLMServiceFactory llmServiceFactory;

    private final ObjectMapper om = new ObjectMapper();


    @Override
    public String kind() {
        return "llm";
    }

    @Override
    public CaseOutput execute(EvalCase evalCase, String targetModel) throws Exception {
        // 1. messages 反序列化：TypeReference 在 core 包
        List<Message> messages = om.convertValue(
                evalCase.getInput().path("messages"),
                new TypeReference<List<Message>>() {
                }
        );

        // 2. 按白名单模型名取对应 provider 的适配器
        LLMService service = llmServiceFactory.getLlmService(targetModel);

        // 3. 记时用单调钟；tools 传 null = 裸 LLM
        long start = System.nanoTime();
        ChatResponse response = service.chat(targetModel, messages, null);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        // 4. token 用 Integer 原样透传，保留“上游没给” 与 “真的是 0”的区别
        return CaseOutput.builder()
                .content(response.getContent())
                .toolNames(List.of())
                .latencyMs(latencyMs)
                .inputTokens(response.getInputTokens())
                .outputTokens(response.getOutputTokens())
                .build();
    }
}
