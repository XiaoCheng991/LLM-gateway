package com.kyon.llmgateway.controller;

import com.kyon.llmgateway.config.ModelConfig;
import com.kyon.llmgateway.model.ApiResult;
import com.kyon.llmgateway.model.ModelInfo;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * GET /api/models - 列出可用模型
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {
    @Resource
    private ModelConfig modelConfig;

    /**
     * 列出可用模型
     * @return 模型列表
     */
    @GetMapping("")
    public ApiResult<List<ModelInfo>> list() {
        List<ModelInfo> models = modelConfig.getModels();
        if (models == null || models.isEmpty()) {
            return ApiResult.success("暂无可用模型", new ArrayList<>()); // 返回空列表而不是错误
        }
        return ApiResult.success(models);
    }
}
