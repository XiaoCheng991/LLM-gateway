package com.kyon.llmgateway.eval.dataset;

import com.kyon.llmgateway.eval.model.Criterion;
import com.kyon.llmgateway.eval.model.EvalCase;
import com.kyon.llmgateway.eval.model.EvalDataset;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 读取 eval/datasets/*.json 并做 schema 校验
 * 校验失败直接抛 IllegalArgumentException --- 数据集格式错误不应该静默通过
 */
public class EvalDatasetLoader {

    private static final Set<String> ALLOWED_KIND = Set.of("llm", "agent");
    private static final Set<String> ALLOWED_TYPE = Set.of("exact", "contains", "tool_call", "llm_judge");

    private final ObjectMapper om = new ObjectMapper();

    public EvalDataset load(Path datasetFile) {
        if (!Files.exists(datasetFile)) {
            throw new IllegalArgumentException(
                    "数据集文件不存在" + datasetFile.toAbsolutePath());
        }

        // 1. 读取文件数据
        EvalDataset dataset;
        try {
            String json = Files.readString(datasetFile);
            dataset = om.readValue(json, EvalDataset.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "数据集 JSON 解析失败：" + datasetFile.toAbsolutePath() + "-" + e.getMessage(), e);
        }

        // 校验数据集
        validate(dataset);
        return dataset;
    }

    /**
     * 校验每个字段
     */
    private void validate(EvalDataset d) {
        if (d == null) throw new IllegalArgumentException("数据集为空");
        if (isBlank(d.getName())) throw new IllegalArgumentException("数据集缺少 name 字段");
        if (isBlank(d.getVersion())) throw new IllegalArgumentException("数据集缺少 version 字段");
        if (d.getCases() == null || d.getCases().isEmpty()) throw new IllegalArgumentException("数据集 cases 为空");

        Set<String> seenIds = new HashSet<>();
        List<EvalCase> cases = d.getCases();

        for (int i = 0; i < cases.size(); i++) {
            EvalCase c = cases.get(i);
            String at = "用例 #" + (i + 1) + (c != null && !isBlank(c.getId()) ? "（id=" + c.getId() + "）" : "");
            if (c == null) throw new IllegalArgumentException("用例 #" + (i + 1) + "为 null");
            if (isBlank(c.getId())) throw new IllegalArgumentException(at + " 缺少 id");

            if (!seenIds.add(c.getId())) throw new IllegalArgumentException(at + " id 重复");

            if (isBlank(c.getKind())) throw new IllegalArgumentException(at + " 缺少 kind");
            if (!ALLOWED_KIND.contains(c.getKind())) throw new IllegalArgumentException(at + " kind 非法：" + c.getKind()
                    + "（允许 llm / agent）");
            if (isBlank(c.getCategory())) throw new IllegalArgumentException(at + " 缺少 category");
            if (c.getInput() == null || c.getInput().isNull()) throw new IllegalArgumentException(at + " 缺少 input");
            if (c.getCriteria() == null || c.getCriteria().isEmpty())
                throw new IllegalArgumentException(at + " 缺少 criteria");

            validateInput(c, at);
            for (Criterion cr : c.getCriteria()) validateCriterion(cr, at);
        }
    }

    /**
     * 校验 input 输入
     */
    private void validateInput(EvalCase c, String at) {
        if ("llm".equals(c.getKind())) {
            if (!c.getInput().path("messages").isArray())
                throw new IllegalArgumentException(at + " kind=llm时 input.messages 必须是数组");
        } else {
            if (isBlank(c.getInput().path("message").asString(null)))
                throw new IllegalArgumentException(at + " kind=agent时, input.message 必填");
        }
    }

    /**
     * 校验 criterion 单条评分标准
     */
    private void validateCriterion(Criterion cr, String at) {
        if (cr == null) throw new IllegalArgumentException(at + " 存在 criterion 为 null");
        if (isBlank(cr.getType())) throw new IllegalArgumentException(at + " 存在缺少 type 的 criterion");
        if (!ALLOWED_TYPE.contains(cr.getType()))
            throw new IllegalArgumentException(at + " criterion type 非法：" + cr.getType() + "(允许 exact / contains / tool_call / llm_judge)");

        switch (cr.getType()) {
            case "exact", "contains" -> {
                if (cr.getValue() == null || cr.getValue().isEmpty())
                    throw new IllegalArgumentException(at + " type=" + cr.getType() + " 需要非空 value");
            }
            case "tool_call" -> {
                if (isBlank(cr.getTool())) throw new IllegalArgumentException(at + " type=tool_call 需要 tool 字段");
            }
            case "llm_judge" -> {
                if (cr.getDimension() == null)
                    throw new IllegalArgumentException(at + " type=llm_judge 需要 dimension 字段");
            }
            default -> {
            }
        }
    }

    // String 字符串类型 判空
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
