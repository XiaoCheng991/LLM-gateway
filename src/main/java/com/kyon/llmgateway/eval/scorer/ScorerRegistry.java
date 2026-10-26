package com.kyon.llmgateway.eval.scorer;

import com.kyon.llmgateway.eval.model.CaseOutput;
import com.kyon.llmgateway.eval.model.Criterion;
import com.kyon.llmgateway.eval.model.ScorerResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ScorerRegistry {

    /**
     * 数据集允许出现的全部 type；少一个实现就启动失败，不留到运行时
     */
    private static final Set<String> REQUIRED = Set.of("exact", "contains", "tool_call", "llm_judge");

    private final Map<String, Scorer> byType = new HashMap<>();

    public ScorerRegistry(List<Scorer> scorers) {
        for (Scorer s : scorers) {
            byType.put(s.type(), s);
        }
        List<String> missing = REQUIRED.stream().filter(t -> !byType.containsKey(t)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("缺少 Scorer 实现：" + missing);
        }
    }

    /**
     * 调用入口，未注册的 type 报错（数据集里有，代码里没实现 = 必须被看见）
     */
    public ScorerResult score(Criterion criterion, CaseOutput output) {
        Scorer s = byType.get(criterion.getType());
        if (s == null) throw new IllegalArgumentException("未注册的 criteria type：" + criterion.getType());

        // 具体调用对应 type，进行打分
        return s.score(criterion, output);
    }
}
