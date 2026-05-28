package com.kyon.llmgateway.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 调用 Nvidia 模型
 */
@Service
public class NvidiaAdapter extends BaseLLMAdapter {
    // 模型
    private static final String MODEL = "deepseek-ai/deepseek-v4-flash";

    @Value("${llm.nvidia.base-url}")
    private String BASE_URL;

    @Value("${llm.nvidia.api-key}")
    private String API_KEY;

    @Override
    protected String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    protected String getApiKey() {
        return API_KEY;
    }

    @Override
    protected String getModelName() {
        return MODEL;
    }
}
