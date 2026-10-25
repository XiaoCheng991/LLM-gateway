package com.kyon.llmgateway.eval.scorer;

import com.kyon.llmgateway.eval.model.CaseOutput;
import com.kyon.llmgateway.eval.model.Criterion;
import com.kyon.llmgateway.eval.model.ScorerResult;
import org.springframework.stereotype.Component;

@Component
public class ContainsScorer implements Scorer {

    @Override
    public String type() { return "contains"; }

    @Override
    public ScorerResult score(Criterion criterion, CaseOutput output) {
        String actual = TextNormalizer.normalize(output.getContent());

        for (String keyword : criterion.getValue()) {
            if (actual.contains(TextNormalizer.normalize(keyword))) {
                return ScorerResult.of(1.0, "包含关键词「" + keyword + "」");
            }
        }
        return ScorerResult.of(0.0, "未包含任一关键词：" + String.join("/", criterion.getValue()));
    }
}
