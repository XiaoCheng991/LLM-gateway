package com.kyon.llmgateway.eval.runner;

import com.kyon.llmgateway.eval.model.*;
import com.kyon.llmgateway.eval.scorer.LlmJudgeScorer;
import com.kyon.llmgateway.eval.scorer.ScorerRegistry;
import com.kyon.llmgateway.eval.scorer.TextNormalizer;
import com.kyon.llmgateway.observability.UpstreamHttpException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.*;

/**
 * 评测编排器：跑用例、判分、汇总报告。
 * 四个方法，四层职责，互不越界：
 *   run()         —— 遍历 + 限速 + 汇总。只管流程，不碰判分细节。
 *   runOne()      —— 单条用例：执行 -> 判分 -> 定状态。只管一条。
 *   scoreOne()    —— 单条标准：找到对应 Scorer 并调用。判分的唯一入口。
 *   buildReport() —— 把用例结果算成报告。纯计算，不做 IO、不做判断。
 * 判定规则（唯一真理，只在 runOne 里实现，别处不得重复）：
 *   1. 执行抛异常          -> ERROR（没测成，不进分母）
 *   2. skipped 的标准      -> 不参与判定，但记进 skippedReasons
 *   3. llm_judge 标准      -> 只记录，不参与判定（judge 是观测仪器，不是裁判）
 *   4. 确定性标准全过      -> PASS；有一条没过 -> FAIL
 *   5. 一条确定性标准都没有 -> FAIL（否则 judge-only 用例会永远通过）
 */
@Component
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    // judge 标准的 type；字符串常量住在 judge 判分器旁边，避免散落各处写死
    private static final String JUDGE_TYPE = LlmJudgeScorer.TYPE;

    // 报告里每条用例保留的输出长度上限
    private static final int OUTPUT_PREVIEW = 200;

    /** 上游瞬时故障（429/5xx、连不上）重试次数。0 = 不重试。命令行可覆盖：--eval.max-retries=2 */
    @Value("${eval.max-retries:2}")
    private int maxRetries;

    /** 重试退避基数（毫秒）。第 n 次重试等待 base*n。命令行可覆盖：--eval.retry-base-ms=15000 */
    @Value("${eval.retry-base-ms:15000}")
    private long retryBaseMs;

    @Resource
    private ScorerRegistry scorerRegistry;
    @Resource
    private LlmJudgeScorer llmJudgeScorer;

    private final Map<String, CaseExecutor> executors = new HashMap<>();

    public EvalRunner(List<CaseExecutor> executorList) {
        for (CaseExecutor e : executorList) executors.put(e.kind(), e);
    }

    /** 一次执行的结果：要么拿到输出，要么拿到失败原因与尝试次数。 */
    private record ExecOutcome(CaseOutput output, Exception error, int attempts) { }

    // ------------------------ 流程层 ------------------------------------
    public EvalReport run(EvalDataset dataset, String targetModel, String judgeModel, long delayMs) {
        List<EvalCase> cases = dataset.getCases();
        List<CaseResult> results = new ArrayList<>(cases.size());
        boolean interrupted = false;

        for (int i = 0; i < cases.size(); i++) {
            EvalCase c = cases.get(i);
            CaseResult r = runOne(c, targetModel, judgeModel);
            results.add(r);

            // 每条都打进度：30-50 条规模下成本可以忽略，出错就可以直接定为到用例
            log.info("[{}/{}] {} ({}) {} ({}ms)", i + 1, dataset.getCases().size(), c.getId(), c.getCategory(), r.getStatus(), r.getLatencyMs());

            // 限速：上游限速/排队，并行或过密都会撞 429
            if (delayMs > 0 && i < dataset.getCases().size() - 1) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    log.warn("评测被中断，已完成 {}/{} 条", results.size(), cases.size());
                    break;
                }
            }
        }

        EvalReport report = buildReport(dataset, targetModel, judgeModel, results);
        if (interrupted) {
            String extra = "评测被中断，仅完成 " + results.size() + "/" + cases.size() + " 条";
            report.setNote(report.getNote() == null ? extra : report.getNote() + "; " + extra);
        }
        return report;
    }

    // ------------------------ 单条层 ------------------------

    /**
     * 跑一条用例并定状态。三段：执行 -> 判分 -> 定状态。
     * 执行失败记 ERROR（不进分母）；判分层异常不捕获（那是我们的 bug，整轮 fail fast）。
     */
    private CaseResult runOne(EvalCase c, String targetModel, String judgeModel) {
        CaseExecutor executor = executors.get(c.getKind());
        if (executor == null) {
            // 数据集里有这个 kind，但是代码没实现 -> 我们的bug，快速失败 fail fast
            throw new IllegalArgumentException("用例 " + c.getId() + " kind=" + c.getKind() +
                    " 没有对应 CaseExecutor");
        }

        ExecOutcome outcome = executeWithRetry(c, targetModel);
        if (outcome.error() != null) {
            // 执行层失败 = 这轮没测成，不是模型能力问题 -> ERROR, 不进分母
            Exception e = outcome.error();
            log.warn("用例 {} 执行失败（尝试 {} 次）: {}", c.getId(), outcome.attempts(), e.getMessage());
            return CaseResult.builder()
                    .caseId(c.getId())
                    .category(c.getCategory())
                    .kind(c.getKind())
                    .status(CaseResult.ERROR)
                    .errorMessage(e.getMessage())
                    .output("")
                    .latencyMs(0L)
                    .scores(Map.of())
                    .skippedReasons(List.of())
                    .note(outcome.attempts() > 1 ? "执行失败（已重试 " + (outcome.attempts() - 1) + " 次）" : null)
                    .build();
        }

        CaseOutput output = outcome.output();

        // 判分：每条标准只调一次；结果按数据集顺序存放（LinkedHashMap，保证报告顺序稳定）
        Map<String, ScorerResult> scores = new LinkedHashMap<>();
        List<String> skippedReasons = new ArrayList<>();
        List<String> failedDeterministic = new ArrayList<>();
        int deterministicCount = 0;

        // 该 case 对应的 一系列评分标准
        List<Criterion> criteria = c.getCriteria();
        for (int idx = 0; idx < criteria.size(); idx++) {
            // 拿到单独的每一条
            Criterion cr = criteria.get(idx);
            // key 用 criteria 下标：同类多条也不会互相覆盖，报告能对回数据集
            String key = cr.getType() + "#" + idx;

            ScorerResult sr = scoreOne(c, cr, output, judgeModel);
            scores.put(key, sr);

            if (sr.isSkipped()) {
                // 没测的：不进判定，但必须留在原因，报告才解释的清
                skippedReasons.add(key + " "+ cr.getType() + ": " + sr.getReason());
                continue;    // 不 continue，那 skipped 的 0.0 分会把用例判成 FAIL
            }
            if (JUDGE_TYPE.equals(cr.getType())) continue;   // judge 只观察，不参与通过判定
            deterministicCount++;
            if (sr.getScore() < 1.0) {
                failedDeterministic.add(key + " score=" + sr.getScore() + " - " + sr.getReason());
            }
        }

        // 通过与否只由确定性标注决定
        String status;
        String note;
        if (deterministicCount == 0) {
            status = CaseResult.FAIL;   // 无确定性 criteria：不能算过（否则 judge-only 用例恒通过）
            note = "无确定性 criteria (仅 judge 标准), 不计入通过判定";
        } else if (failedDeterministic.isEmpty()){
            status = CaseResult.PASS;
            note = null;
        } else {
            status = CaseResult.FAIL;
            note = "未通过的确定性标准：" + String.join("; ", failedDeterministic);
        }

        return CaseResult.builder()
                .caseId(c.getId())
                .category(c.getCategory())
                .kind(c.getKind())
                .status(status)
                .output(TextNormalizer.preview(output.getContent() == null ? "" : output.getContent(), OUTPUT_PREVIEW))
                .scores(scores)
                .skippedReasons(skippedReasons)
                .latencyMs(output.getLatencyMs())
                .note(note)
                .build();
    }

    /**
     * 执行一条用例，对「上游瞬时故障」做退避重试。
     *
     * 为什么要重试：ERROR 不进分母。上游 429 / 连不上属瞬时故障，直接判死会让分母悄悄缩水，
     * 报告看起来像「模型变差了」，真相是「这几条没跑成」。
     * 为什么不重试一切：模型不在白名单、请求非法这类是永久错误，重试只是浪费时间。
     */
    private ExecOutcome executeWithRetry(EvalCase c, String targetModel) {
        CaseExecutor executor = executors.get(c.getKind());
        int attempts = 0;
        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            attempts = attempt;
            try {
                return new ExecOutcome(executor.execute(c, targetModel), null, attempts);
            } catch (Exception e) {
                boolean retryable = isRetryable(e);
                if (!retryable || attempt > maxRetries) {
                    return new ExecOutcome(null, e, attempts);
                }
                long waitMs = retryBaseMs * attempt;
                log.warn("用例 {} 第 {}/{} 次尝试失败（{}），{}ms 后重试",
                        c.getId(), attempt, maxRetries + 1, e.getMessage(), waitMs);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new ExecOutcome(null, e, attempts);
                }
            }
        }
        // 理论上到不了：最后一次失败已在循环内 return
        return new ExecOutcome(null, new IllegalStateException("重试循环异常退出"), attempts);
    }

    /**
     * 哪些故障值得重试：
     *   - 上游 429/503/504：上游暂时不给，退避后有意义；
     *   - 连不上 / 超时：网络抖动，重试有意义；
     *   - 其它（模型不在白名单、请求非法、4xx）：永久错误，重试无意义。
     * 刻意只认具体类型，不用笼统 IOException —— 笼统 catch 会把真实缺陷一起吞掉。
     */
    private boolean isRetryable(Exception e) {
        if (e instanceof UpstreamHttpException up) {
            return up.isRetryable();
        }
        return e instanceof ConnectException || e instanceof HttpTimeoutException;
    }

    /**
     * 判分的唯一入口：按 type 找 Scorer 并调用。
     * judge 需要原始问题（EvalCase），Scorer 接口给不了 -> 走 LlmJudgeScorer 的 4 参重载。
     * 判分层异常（未注册 type 等）不在此捕获 -> 整轮 fail fast：
     * 判分是「我们的代码」，它的异常若记成 ERROR，会让同一个 bug 把大批用例染成 ERROR，
     * 报告看起来像「模型全面失效」，把人引向错误方向。
     */
    private ScorerResult scoreOne(EvalCase c, Criterion cr, CaseOutput output, String judgeModel) {
        if (JUDGE_TYPE.equals(cr.getType())) {
            // judge 特判：需要原始问题（EvalCase），且失败只算 skipped
            return llmJudgeScorer.scoreWithFallback(c, cr, output, judgeModel);
        }
        return scorerRegistry.score(cr, output);
    }

    // ---------------------------- 汇总层 -----------------------------------------

    /**
     * 把用例结果算成报告。纯计算：只数数、求平均，不做 IO，不改判分结论。
     * ERROR 不进分母（分母 = passed + failed）。
     */
    private EvalReport buildReport(EvalDataset d, String targetModel, String judgeModel, List<CaseResult> results) {
        int passed = 0, failed = 0, errors = 0;
        Map<String, CategoryStat> byCategory = new TreeMap<>();
        Map<String, List<Double>> byDimension = new HashMap<>();

        for (CaseResult cr : results) {
            switch (cr.getStatus()) {
                case CaseResult.PASS -> passed++;
                case CaseResult.ERROR -> errors++;
                default -> failed++;
            }
            if (!CaseResult.ERROR.equals(cr.getStatus())) {
                CategoryStat st = byCategory.computeIfAbsent(cr.getCategory(), k -> new CategoryStat());
                st.setTotal(st.getTotal() + 1);          // 参与判定的总数（不含 ERROR）
                if (CaseResult.PASS.equals(cr.getStatus())) st.setPassed(st.getPassed() + 1);
            }
            // 维度均分：只统计 judge 且从未跳过的结果（只有 judge 会带维度）
            for (ScorerResult sr : cr.getScores().values()) {
                if (sr.getDimension() != null && !sr.isSkipped()) {
                    byDimension.computeIfAbsent(sr.getDimension().name(), x -> new ArrayList<>())
                            .add(sr.getScore());
                }
            }
        }

        // 每个 category 算自己的通过率
        for (CategoryStat st : byCategory.values()) {
            st.setPassRate(st.getTotal() == 0 ? 0.0 : (double) st.getPassed() / st.getTotal());
        }


        Map<String, Double> dimAvg = new TreeMap<>();
        byDimension.forEach((k, list) -> dimAvg.put(k,
                list.stream().mapToDouble(Double::doubleValue).average().orElse(0.0)));

        int judged = passed + failed;   // ERROR 不进分母
        double rate = judged == 0 ? 0.0 : (double) passed / judged;

        // 全部 ERROR 时 0.0 会被误读成 “模型全错”，必须显示提示
        String note;
        if (judged == 0) {
            note = "全部用例执行失败 (errors=" + errors + "), 通过率无参考价值";
            log.warn(note);
        } else if (errors > 0) {
            note = errors + " 条用例执行失败（ERROR），未计入通过率分母";
            log.warn(note);
        } else {
            note = null;
        }

        return EvalReport.builder()
                .datasetName(d.getName()).datasetVersion(d.getVersion())
                .targetModel(targetModel).judgeModel(judgeModel)
                .ranAt(Instant.now().toString())
                .totalCases(results.size())
                .passed(passed).failed(failed).errors(errors)
                .passRate(rate)
                .categoryStats(byCategory)
                .dimensionScores(dimAvg)
                .results(results)
                .note(note)
                .build();

    }
}
