package com.kyon.llmgateway.eval;

import com.kyon.llmgateway.config.ModelConfig;
import com.kyon.llmgateway.eval.datasets.EvalDatasetLoader;
import com.kyon.llmgateway.eval.model.EvalDataset;
import com.kyon.llmgateway.eval.model.EvalReport;
import com.kyon.llmgateway.eval.reports.ConsoleReporter;
import com.kyon.llmgateway.eval.reports.JsonReporter;
import com.kyon.llmgateway.eval.runner.EvalRunner;
import com.kyon.llmgateway.model.ModelInfo;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;

/**
 * Eval 命令行入口。
 *
 * 用法：
 *   mvn spring-boot:run --eval.dataset=chat-v1 --eval.model=nvidia/minimaxai/minimax-m3.1-flash
 *
 * 三条铁律：
 *   1. 不传 --eval.dataset 就静默返回 —— 普通启动绝不能顺手评测；
 *   2. 模型名先查白名单 —— 跑 10 条才发现模型名写错是白烧额度；
 *   3. 跑完写 JSON 报告 —— 报告是唯一可复查的凭据。
 *
 * 退出码：0 = 跑完了（PASS/FAIL 都算）；1 = 有用例执行失败（ERROR）；2 = 参数或数据集错误。
 */
@Component
public class EvalCli implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalCli.class);

    @Value("${eval.dataset:}")
    private String datasetName;   // 空 = 不跑评测
    @Value("${eval.model:}")
    private String targetModel;
    @Value("${eval.judge-model:}")
    private String judgeModel;
    @Value("${eval.dataset-dir:eval/datasets}")
    private String datasetDir;
    @Value("${eval.report-dir:eval/reports}")
    private String reportDir;
    @Value("${eval.delay-ms:500}")
    private long delayMs;

    @Resource
    private EvalRunner evalRunner;
    @Resource
    private ConsoleReporter consoleReporter;
    @Resource
    private JsonReporter jsonReporter;
    @Resource
    private ModelConfig modelConfig;

    @Override
    public void run(String... args) {
        // 铁律 1：没指定数据集 -> 普通启动，静默返回
        if (datasetName == null || datasetName.isBlank()) {
            log.info("未指定 --eval.dataset，本次启动不执行评测");
            return;
        }

        try {
            if (targetModel == null || targetModel.isBlank()) {
                throw new IllegalArgumentException(
                        "--eval.model 必填（被测模型全名，如 nvidia/minimaxai/minimax-m3.1-flash）");
            }

            // 铁律 2：模型先查白名单
            requireWhitelisted(targetModel);
            if (judgeModel != null && !judgeModel.isBlank()) {
                requireWhitelisted(judgeModel);
            }

            // 数据集路径：--eval.dataset=chat-v1 -> eval/datasets/chat-v1.json；也容忍写成 chat-v1.json
            String name = datasetName.endsWith(".json")
                    ? datasetName.substring(0, datasetName.length() - ".json".length())
                    : datasetName;
            Path datasetFile = Path.of(datasetDir, name + ".json");
            EvalDataset dataset = new EvalDatasetLoader().load(datasetFile);

            EvalReport report = evalRunner.run(dataset, targetModel, judgeModel, delayMs);

            consoleReporter.report(report);

            // 铁律 3：报告落盘
            Path jsonFile = Path.of(reportDir, fileBase(name, targetModel) + ".json");
            jsonReporter.write(report, jsonFile);
            log.info("JSON 报告已写入: {}", jsonFile.toAbsolutePath());

            System.exit(report.getErrors() > 0 ? 1 : 0);

        } catch (Exception e) {
            log.error("Eval 失败: {}", e.getMessage());
            System.exit(2);
        }
    }

    /**
     * 模型必须在 yml 白名单里：白名单是路由的唯一依据，不在白名单 = 没有 provider 可用。
     * 报错时把可用模型列出来，省得再翻 yml。
     */
    private void requireWhitelisted(String model) {
        try {
            modelConfig.getProviderByModel(model);
        } catch (IllegalArgumentException e) {
            String available = modelConfig.getModels() == null ? "" : modelConfig.getModels().stream()
                    .map(ModelInfo::getModel)
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(e.getMessage() + "（可用模型：" + available + "）");
        }
    }

    /** 报告文件名：<dataset>-<modelSlug>-<timestamp>，modelSlug 把 / 换成 -- */
    private String fileBase(String dataset, String model) {
        String slug = model.replace("/", "--").replace(':', '-');
        String ts = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString().replace(":", "-");
        return dataset + "-" + slug + "-" + ts;
    }
}
