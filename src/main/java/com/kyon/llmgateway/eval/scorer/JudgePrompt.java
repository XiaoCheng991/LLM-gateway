package com.kyon.llmgateway.eval.scorer;

import com.kyon.llmgateway.eval.model.Criterion;
import com.kyon.llmgateway.eval.model.Dimension;
import com.kyon.llmgateway.eval.model.EvalCase;

/**
 * LLM-as-a-judge提示词模版
 * 三条纪律：1 判分标准放到 SYSTEM；2 只输出 JSON；3 绝不给 judge 看标准答案
 */
public final class JudgePrompt {

    private JudgePrompt() { }

    public static final String SYSTEM = """
            You are a strict evaluator. Judge the ASSISTANT answer against the USER question.
            Score from 0.0 to 1.0:
              1.0 = fully correct, relevant and faithful
              0.5 = partially correct or partly irrelevant
              0.0 = wrong, irrelevant, or made up facts not supported by the input
            Rules:
              - "score" MUST be a JSON number between 0 and 1 (never a string, never missing).
              - If score < 1.0, the reason MUST state what is wrong with the answer.
            Output ONLY a JSON object, no markdown, no explanation:
            {"score": <0.0-1.0>, "reason": "<one short sentence>"}
            """;

    /**
     *  user 内容：原始问题 + 模型答案 + 维度说明（+ 附加要求）
     *  注意：不带 criterion.getValue() -- 标准答案泄漏会让 judge 退化成字符串打勾
     */
    public static String user(EvalCase evalCase, Criterion criterion, String output, String dimensionDesc) {
        String question = evalCase.getInput().has("message")
                ? evalCase.getInput().path("message").asString()
                : evalCase.getInput().path("messages").path(0).path("content").asString();

        StringBuilder sb = new StringBuilder();
        sb.append("USER QUESTION:\n").append(question).append("\n\n");
        sb.append("ASSISTANT ANSWER:\n").append(output).append("\n\n");
        sb.append("JUDGE DIMENSION: ").append(dimensionDesc).append('\n');
        if (criterion.getPrompt() != null && !criterion.getPrompt().isBlank()) {
            sb.append("ADDITIONAL CRITERIA: ").append(criterion.getPrompt()).append("\n");
        }
        return sb.toString();
    }

    // Dimension -> 英文描述（judge 是英文模型，描述也用英文更稳）
    public static String dimensionDesc(Dimension d) {
        if (d == null) {
            // 没写 dimension 时给个通用维度，而不是让 switch(null) 抛 NPE：
            // NPE 会被上层 catch 成「judge 调用失败」，报错指向完全错误的方向
            return "overall answer quality";
        }
        return switch (d) {
            case ACCURACY -> "factual accuracy";
            case RELEVANCE -> "relevance to the question";
            case FAITHFULNESS -> "faithfulness: does not invent facts beyond the input";
            case TOOL_CHOICE -> "choosing the right tool with sensible argument";
        };
    }
}
