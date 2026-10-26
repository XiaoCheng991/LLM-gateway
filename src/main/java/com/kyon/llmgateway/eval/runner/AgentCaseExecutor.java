package com.kyon.llmgateway.eval.runner;

import com.kyon.llmgateway.agent.*;
import com.kyon.llmgateway.agent.impl.ReActAgent;
import com.kyon.llmgateway.eval.model.CaseOutput;
import com.kyon.llmgateway.eval.model.EvalCase;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * kind=agent：走完完整的 Agent 链路（工具循环 + 会话 + 状态机）
 * 与生产 AgentController 保持同一分派：react 与plan_execute 都由 ReActAgent 承担
 */
@Component
public class AgentCaseExecutor implements CaseExecutor {

    @Resource
    private ReActAgent reActAgent;


    @Override
    public String kind() {
        return "agent";
    }

    @Override
    public CaseOutput execute(EvalCase evalCase, String targetModel) throws Exception {
        String message = evalCase.getInput().path("message").asString();
        String mode = evalCase.getInput().path("mode").asString("react");

        AgentRequest request = new AgentRequest();
        request.setMessage(message);
        request.setModel(targetModel);
        // 显示设置 mode，不依赖缺省（缺省是 REACT 而非 null）
        request.setMode("plan-execute".equals(mode) ? AgentMode.PLAN_EXECUTE : AgentMode.REACT);
        // sessionId 留空 -> Agent 内部新建，用例之间不共享上下文

        long start = System.nanoTime();
        AgentResponse response = reActAgent.chat(request);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        // toolNames 按调用顺序；两层 null 防护（toolCall 本身、工具名）
        List<String> toolNames = response.getToolHistory() == null
                ? List.of()
                : response.getToolHistory().stream()
                .map(ToolCallRecord::toolCall)
                .filter(tc -> tc != null && tc.getName() != null)
                .map(ToolCall::getName)
                .toList();

        return CaseOutput.builder()
                .content(response.getContent())
                .toolNames(toolNames)
                .latencyMs(latencyMs)
                .inputTokens(response.getInputTokens())
                .outputTokens(response.getOutputTokens())
                .build();
    }
}
