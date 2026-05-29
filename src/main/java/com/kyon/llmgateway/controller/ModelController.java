package com.kyon.llmgateway.controller;

import com.kyon.llmgateway.config.ModelConfig;
import com.kyon.llmgateway.model.ApiResult;
import com.kyon.llmgateway.model.ModelInfo;
import com.kyon.llmgateway.model.enums.ResultCode;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    @GetMapping("/list")
    public ApiResult<List<ModelInfo>> list() {
        List<ModelInfo> models = modelConfig.getModels();
        if (models == null || models.isEmpty()) {
            return ApiResult.error(ResultCode.INTERNAL_ERROR, "配置中暂无可用模型");
        }
        return ApiResult.success(models);
    }
}
