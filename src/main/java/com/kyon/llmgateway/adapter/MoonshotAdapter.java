package com.kyon.llmgateway.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 调用月之暗面 Kimi 模型
 */
@Service
public class MoonshotAdapter extends BaseLLMAdapter{
    // 模型
    private static final String MODEL = "moonshot-ai/kimi-k2.6";

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
