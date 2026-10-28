package com.kyon.llmgateway.eval.reports;

import com.kyon.llmgateway.eval.model.EvalReport;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON 报告：把 EvalReport 写成文件。
 * 缩进输出：报告要被人读，也要能被机器读回来做基线对比。
 *
 * 注意：Jackson 3 的 ObjectMapper 没有 enable()/configure()，必须用 JsonMapper.builder()。
 * （手册 T10.1 写的是 new ObjectMapper().enable(...)，在本项目编译不过。）
 */
@Component
public class JsonReporter {

    private final JsonMapper jm = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    public void write(EvalReport report, Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, jm.writeValueAsString(report));
        } catch (Exception e) {
            // 写报告失败是 硬错误：静默跳过会让人以为「跑过了」
            throw new IllegalStateException(
                    "写 JSON 报告失败：" + file.toAbsolutePath() + " - " + e.getMessage(), e
            );
        }
    }
}
