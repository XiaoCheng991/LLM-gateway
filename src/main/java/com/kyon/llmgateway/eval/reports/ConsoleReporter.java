package com.kyon.llmgateway.eval.reports;

import com.kyon.llmgateway.eval.model.CaseResult;
import com.kyon.llmgateway.eval.model.EvalReport;
import com.kyon.llmgateway.eval.model.ScorerResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 终端报告：只打印，不写文件。
 * 打印顺序 = 阅读顺序：先结论（多少条过），再分组（哪类差），最后明细（哪条坏了）。
 * 另有一处最易漏：被跳过的标准也要打印。
 * 「没测」和「测了没过」是两回事，混起来会把「评测没测成」误读成「模型不行」。
 */
@Component
public class ConsoleReporter {

    public void report(EvalReport r) {
        List<CaseResult> all = r.getResults() == null ? List.of() : r.getResults();
        StringBuilder sb = new StringBuilder();

        // 1. 头部：这轮评测是谁、在哪跑的
        sb.append("\n==================== EVAL REPORT ====================\n");
        sb.append(String.format("Dataset  : %s %s%n", r.getDatasetName(), r.getDatasetVersion()));
        sb.append(String.format("Target   : %s%n", r.getTargetModel()));
        sb.append(String.format("Judge    : %s%n", r.getJudgeModel() == null ? "(none)" : r.getJudgeModel()));
        sb.append(String.format("Ran at   : %s%n%n", r.getRanAt()));

        // 2. 结论：四个数
        sb.append(String.format("Total    : %d cases%n", r.getTotalCases()));
        sb.append(String.format("Passed   : %d%n", r.getPassed()));
        sb.append(String.format("Failed   : %d%n", r.getFailed()));
        sb.append(String.format("Errors   : %d%n", r.getErrors()));
        sb.append(String.format("Pass rate: %.1f%% (errors 不计入分母)%n", r.getPassRate() * 100));

        // 3. 报告级提示：ERROR 多、或全错时，通过率会误导人，必须显式说清
        if (r.getNote() != null) {
            sb.append(String.format("Note     : %s%n", r.getNote()));
        }
        sb.append("\n");

        // 4. 分类通过率：哪一类差，一眼看出
        sb.append("-- by category --\n");
        if (r.getCategoryStats() == null || r.getCategoryStats().isEmpty()) {
            sb.append("(无)\n");
        } else {
            r.getCategoryStats().forEach((cat, st) -> {
                double pct = st.getPassRate();
                sb.append(String.format("%-16s %d/%d  %s %.0f%%%n",
                        cat, st.getPassed(), st.getTotal(), bar(pct), pct * 100));
            });
        }

        // 5. judge 维度均分：只在真的判过分时出现，空 map 不占版面
        if (r.getDimensionScores() != null && !r.getDimensionScores().isEmpty()) {
            sb.append("\n-- judge dimensions --\n");
            r.getDimensionScores().forEach((d, v) ->
                    sb.append(String.format("%-14s %.2f%n", d, v)));
        }

        // 6. 失败明细：FAIL 与 ERROR 都列；理由取第一条失分标准或错误信息
        List<CaseResult> bad = all.stream()
                .filter(x -> !CaseResult.PASS.equals(x.getStatus()))
                .toList();
        sb.append("\n-- failed / error cases --\n");
        if (bad.isEmpty()) {
            sb.append("(全部通过)\n");
        } else {
            bad.forEach(x -> sb.append(String.format("%-14s %-6s %s%n",
                    x.getCaseId(), x.getStatus(), firstReason(x))));
        }

        // 7. 被跳过的标准：没测的东西必须显形，否则读者会把「没测」当成「没做错」
        int skipped = all.stream()
                .mapToInt(x -> x.getSkippedReasons() == null ? 0 : x.getSkippedReasons().size())
                .sum();
        if (skipped > 0) {
            sb.append(String.format("%n-- skipped criteria: %d 条（未参与通过判定）--%n", skipped));
            all.forEach(x -> {
                if (x.getSkippedReasons() != null) {
                    x.getSkippedReasons().forEach(s -> sb.append("  ").append(s).append("\n"));
                }
            });
        }

        sb.append("=====================================================\n");
        System.out.print(sb);
    }

    /** 20 格进度条 */
    private String bar(double pct) {
        int filled = (int) Math.round(pct * 20);
        return "█".repeat(filled) + "░".repeat(20 - filled);
    }

    /** 理由：ERROR 优先报错误信息；否则报第一条失分标准的理由；都没有就报 note */
    private String firstReason(CaseResult x) {
        if (x.getErrorMessage() != null) return x.getErrorMessage();
        if (x.getScores() != null) {
            for (ScorerResult s : x.getScores().values()) {
                if (!s.isSkipped() && s.getScore() < 1.0) return s.getReason();
            }
        }
        return x.getNote() == null ? "(无有效 criteria)" : x.getNote();
    }
}
