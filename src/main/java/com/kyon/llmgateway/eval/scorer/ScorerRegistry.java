package com.kyon.llmgateway.eval.scorer;

import com.kyon.llmgateway.eval.model.CaseOutput;
import com.kyon.llmgateway.eval.model.Criterion;
import com.kyon.llmgateway.eval.model.ScorerResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ScorerRegistry {

    private final Map<String, Scorer> byType = new HashMap<>();

    public ScorerRegistry(List<Scorer> scorers) {
        for (Scorer s : scorers) { byType.put(s.type(), s); }
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
