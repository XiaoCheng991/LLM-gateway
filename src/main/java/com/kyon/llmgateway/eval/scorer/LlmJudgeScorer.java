package com.kyon.llmgateway.eval.scorer;

import com.kyon.llmgateway.eval.model.CaseOutput;
import com.kyon.llmgateway.eval.model.Criterion;
import com.kyon.llmgateway.eval.model.EvalCase;
import com.kyon.llmgateway.eval.model.ScorerResult;
import com.kyon.llmgateway.model.ChatResponse;
import com.kyon.llmgateway.model.Message;
import com.kyon.llmgateway.service.LLMServiceFactory;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Component
public class LlmJudgeScorer implements Scorer{
    private static final Logger log = LoggerFactory.getLogger(LlmJudgeScorer.class);

    @Resource
    private LLMServiceFactory llmServiceFactory;

    private final ObjectMapper om = new ObjectMapper();
    private static final int RAW_PREVIEW = 200;
    public static final String TYPE = "llm_judge";


    @Override
    public String type() {
        return TYPE;
    }

    /**
     * 接口要求的 2 参签名：无 judgeModel -> 必然 skipped
     */
    @Override
    public ScorerResult score(Criterion criterion, CaseOutput caseOutput) {
        return scoreWithFallback(null, criterion, caseOutput, null);
    }

    /**
     * 4 参重载 JudgePrompt 需要原始问题 EvalCase，Scorer 接口给不了 -> 加重载而不改接口
     * 约定：judge 失败一律 skipped，不判 fail（judge 是观测仪器，不是裁判）
     */
    public ScorerResult scoreWithFallback(EvalCase evalCase, Criterion criterion, CaseOutput output, String judgeModel) {
        if (judgeModel == null || judgeModel.isBlank()) {
            return ScorerResult.skipped("judge 模型未配置");
        }
        try {
            String content = output.getContent() == null ? "" : output.getContent();
            List<Message> messageList = List.of(
                    new Message("system", JudgePrompt.SYSTEM, null, null, null),
                    new Message("user", JudgePrompt.user(evalCase, criterion, content,
                            JudgePrompt.dimensionDesc(criterion.getDimension())), null, null, null)
            );
            // 真实签名：chat(model, message, tools); tools=null = 纯判分，不给工具
            ChatResponse judge = llmServiceFactory.getLlmService(judgeModel).chat(judgeModel, messageList, null);

            JsonNode root = parseJudgeJson(judge.getContent());
            if (root == null) {
                return ScorerResult.skipped("judge 调用失败：返回内容不是 JSON");
            }

            // 分清楚：合法分数 / 显示 0 分 / 没有分数
            Double score = readScore(root);
            if (score == null) {
                return ScorerResult.skipped("judge 返回缺少合法 score 字段，原始返回：" + TextNormalizer.preview(root.toString(), RAW_PREVIEW));
            }

            double s = Math.max(0.0, Math.min(1.0, score));
            String reason = root.path("reason").asString("(no reason)");

            // dimension 必须回调，否则报告里的维度均分聚合不到
            return ScorerResult.of(score, "judge[" + criterion.getDimension() + "]: " + score + " - " + reason,
                    criterion.getDimension());
        } catch (Exception e) {
            log.warn("judge 调用失败，跳过该 criterion: {}", e.getMessage());
            return ScorerResult.skipped("judge 调用失败：" + e.getMessage());
        }
    }

    /**
     * judge 也是 LLM，格式会飘：可能带 ```json 围栏、可能前后有散文
     * 策略：剥围栏 -> 截取首个 { 到末个 } -> 失败返回 null（由调用方记 skipped）
     */
    private JsonNode parseJudgeJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                s = s.substring(firstNl + 1, lastFence).trim();
            }
        }
        int start = s.indexOf("{");
        int end = s.lastIndexOf("}");
        if (start < 0 || end <= start) return null;
        try {
            return om.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读 judge 返回的分数：数字直接取，字符串数字解析，非数字记 warn 并按 0 处理。
     * 判分 JSON 没有 schema，LLM 真会回 {"score":"0.3"} 这种形态。
     */
    private Double readScore(JsonNode root) {
        JsonNode n = root.path("score");
        if (n.isMissingNode() || n.isNull()) {
            log.warn("judge 返回缺少 score 字段，原始返回：{}", TextNormalizer.preview(root.toString(), RAW_PREVIEW));
            return null;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isString()) {
            try {
                return Double.parseDouble(n.asString().trim());
            } catch (NumberFormatException e) {
                log.warn("judge 返回的 score 不是数字：{}", n.asString());
                return null;
            }
        }
        log.warn("judge 返回的 score 类型异常：{}", n.getNodeType());
        return null;
    }
}
