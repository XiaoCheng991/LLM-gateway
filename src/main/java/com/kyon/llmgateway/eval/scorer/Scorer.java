package com.kyon.llmgateway.eval.scorer;

import com.kyon.llmgateway.eval.model.CaseOutput;
import com.kyon.llmgateway.eval.model.Criterion;
import com.kyon.llmgateway.eval.model.ScorerResult;

/**
 * 评分接口
 */
public interface Scorer {
    // 对应 Criterion.type：exact / contains / tool_call / llm_judge
    String type();

    /**
     * 评分。实现不得抛异常 --- judge 类失败时返回 ScoreResult.skipped()
     * 确定性类失败时返回 score=0 并在 reason 说明原因
     */
    ScorerResult score(Criterion criterion, CaseOutput caseOutput);
}
