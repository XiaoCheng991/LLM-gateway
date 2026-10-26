package com.kyon.llmgateway.eval.scorer;

import com.kyon.llmgateway.eval.model.CaseOutput;
import com.kyon.llmgateway.eval.model.Criterion;
import com.kyon.llmgateway.eval.model.ScorerResult;
import org.springframework.stereotype.Component;

@Component
public class ExactMatchScorer implements Scorer {

    /**
     * 注册键，路由
     */
    @Override
    public String type() {
        return "exact";
    }

    @Override
    public ScorerResult score(Criterion criterion, CaseOutput output) {
        // 归一化真实的输出
        String actual = TextNormalizer.normalize(output.getContent());
        String preview = TextNormalizer.preview(actual);

        // 比较归一化后的 标准值 与 真实输出 是否精确匹配
        for (String expected : criterion.getValue()) {
            if (actual.equals(TextNormalizer.normalize(expected))) {
                return ScorerResult.of(1.0, "精确匹配「" + expected + "」");
            }
        }
        return ScorerResult.of(0.0, "未精确匹配任一候选，实际输出（归一化）=\"" + preview + "\"");
    }
}
