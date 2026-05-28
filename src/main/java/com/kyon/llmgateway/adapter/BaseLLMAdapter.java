package com.kyon.llmgateway.adapter;

import com.kyon.llmgateway.model.ChatRequest;
import com.kyon.llmgateway.model.ChatResponse;
import com.kyon.llmgateway.model.Message;
import com.kyon.llmgateway.service.LLMService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public abstract class BaseLLMAdapter implements LLMService {

    // ObjectMapper
    protected final ObjectMapper om = new ObjectMapper();
    protected HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // 子类仅需要提供三个配置
    protected abstract String getBaseUrl();
    protected abstract String getApiKey();
    protected abstract String getModelName();

    @Override
    public ChatResponse chat(String userMessage) throws Exception {
        // 1. 拼 JSON 请求体
        List<Message> list = new ArrayList<>();
        Message msg = new Message("user", userMessage);
        list.add(msg);

        ChatRequest req = new ChatRequest(getModelName(), list);
        String json = om.writeValueAsString(req);

        // 2. 构建 POST 请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer %s".formatted(getApiKey()))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        // 3. 发送请求（同步）
        long start = System.currentTimeMillis();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long end = System.currentTimeMillis();

        // 4. 解析响应, 解析为ChatResponse
        JsonNode root = om.readTree(response.body());

        String content = root.get("choices").get(0).get("message").get("content").asString();
        Integer inputTokens = root.get("usage").get("prompt_tokens").asInt();
        Integer outputTokens = root.get("usage").get("completion_tokens").asInt();
        String model = root.get("model").asString();

        return ChatResponse.builder()
                .content(content)
                .model(model)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .latency((end - start))     // 单位：毫秒
                .build();
    }
}