package com.kyon.llmgateway.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 调用 Agnes 模型
 */
@Service
public class AgnesAdapter extends BaseLLMAdapter {
    // 模型
    private static final String MODEL = "agnes-2.0-flash";

    @Value("${llm.agnes.base-url}")
    private String BASE_URL;

    @Value("${llm.agnes.api-key}")
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
