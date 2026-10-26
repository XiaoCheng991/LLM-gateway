package com.kyon.llmgateway.eval.scorer;

import com.kyon.llmgateway.eval.model.CaseOutput;
import com.kyon.llmgateway.eval.model.Criterion;
import com.kyon.llmgateway.eval.model.ScorerResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ToolCallScorer implements Scorer {
    @Override
    public String type() {
        return "tool_call";
    }

    @Override
    public ScorerResult score(Criterion criterion, CaseOutput output) {
        List<String> tools = output.getToolNames();
        if (tools == null || tools.isEmpty()) {
            return ScorerResult.of(0.0, "Agent 未调用任何工具, 期望使用「" + criterion.getTool() + "」");
        }
        if (tools.contains(criterion.getTool())) {
            return ScorerResult.of(1.0, "调用了工具「" + criterion.getTool() + "」(实际调用序列：" + String.join(" → ", tools) + ")");
        }

        return ScorerResult.of(0.0, "未调用「" + criterion.getTool() + "」, 实际调用序列：" + String.join(" → ", tools) + ")");
    }
}
