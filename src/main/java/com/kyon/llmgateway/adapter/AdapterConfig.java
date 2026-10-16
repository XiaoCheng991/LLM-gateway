package com.kyon.llmgateway.adapter;

import com.kyon.llmgateway.service.LLMService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdapterConfig {
    @Bean
    public LLMService nvidia(@Value("${llm.nvidia.base-url}") String baseUrl,
                             @Value("${llm.nvidia.api-key}") String apiKey) {
        return new ConfigurableAdapter("nvidia", baseUrl, apiKey);
    }

    @Bean
    public LLMService openrouter(@Value("${llm.openrouter.base-url}") String baseUrl,
                                 @Value("${llm.openrouter.api-key}") String apiKey) {
        return new ConfigurableAdapter("openrouter", baseUrl, apiKey);
    }

    @Bean
    public LLMService agnes(@Value("${llm.agnes.base-url}") String baseUrl,
                            @Value("${llm.agnes.api-key}") String apiKey) {
        return new ConfigurableAdapter("agnes", baseUrl, apiKey);
    }
}
