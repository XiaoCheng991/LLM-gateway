package com.kyon.llmgateway.adapter;

import com.kyon.llmgateway.model.ChatRequest;
import com.kyon.llmgateway.model.ChatResponse;
import com.kyon.llmgateway.model.Message;
import com.kyon.llmgateway.service.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public abstract class BaseLLMAdapter implements LLMService {
    private static final Logger log = LoggerFactory.getLogger(BaseLLMAdapter.class);

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
    public ChatResponse chat(List<Message> userMsgList) throws Exception {
        String modelName = getModelName();
        log.debug("Chat request - model: {}, messages: {}", modelName, userMsgList.size());

        // 1. 拼 JSON 请求体
        ChatRequest req = new ChatRequest(modelName, userMsgList, false);
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
        long latency = System.currentTimeMillis() - start;

        // 4. 解析响应, 解析为ChatResponse
        JsonNode root = om.readTree(response.body());

        String content = root.get("choices").get(0).get("message").get("content").asString();
        Integer inputTokens = root.get("usage").get("prompt_tokens").asInt();
        Integer outputTokens = root.get("usage").get("completion_tokens").asInt();
        String model = root.get("model").asString();

        log.info("Chat completed - model: {}, inputTokens: {}, outputTokens: {}, latency: {}ms",
                model, inputTokens, outputTokens, latency);

        return ChatResponse.builder()
                .content(content)
                .model(model)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .latency(latency)     // 单位：毫秒
                .build();
    }


    // SSE 流式接口 - 默认实现，子类可覆盖
    @Override
    public SseEmitter stream(List<Message> userMsgList) {
        String modelName = getModelName();
        log.debug("Stream request - model: {}, messages: {}", modelName, userMsgList.size());

        // 1. 创建 SseEmitter，设置 5 分钟超时
        SseEmitter emitter = new SseEmitter( 5 * 60 * 1000L);

        // 2. 启动新线程执行流式请求
        new Thread(() -> {
            try {
                // 2.1. 发起 HTTP POST 请求，请求体里加 "stream": true
                ChatRequest req = new ChatRequest(modelName, userMsgList, true);
                String json = om.writeValueAsString(req);

                // 2.2 设置请求头 Accept：text/event-stream
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(getBaseUrl()))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer %s".formatted(getApiKey()))
                        .header("Accept", "text/event-stream")  // 告诉服务端我要流式响应
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                // 2.3 用HttpClient.send() + BodyHandlers.ofInputStream() 获取流式响应
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                // 2.4 逐行读取输入流
                BufferedReader br = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));

                // 2.5 每行以 "data: " 开头时，提取后面的 JSON 字符串
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        // 读到 "data: [DONE]" 时调用 emitter.complete() 结束
                        String data = line.substring(6);
                        if ("[DONE]".equals(data)) {
                            emitter.complete();
                            break;  // 退出线程
                        }

                        try {
                            // 解析 JSON 拿到content，通过 emitter.send() 推给客户端
                            JsonNode root = om.readTree(data);

                            // 处理调用错误
                            if (root.has("error")) {
                                String errMsg = root.get("error").get("message").asString("unknow error");
                                emitter.completeWithError(new RuntimeException("API Error: %s".formatted(errMsg)));
                                return;
                            }

                            // 没报错 就获取数据
                            JsonNode delta = root.get("choices").get(0).get("delta");
                            if (delta != null && delta.has("content")) {
                                String content = delta.get("content").asString();
                                emitter.send(content);
                            }
                        } catch (Exception parseErr) {
                            // JSON 解析失败，继续下一行，不要中断流
                            // 可以日志记录，但不要打断推送, 跳过无法解析的行，不影响后续推送
                            log.warn("【SSE】parse error, skipping line: {}", line, parseErr);
                        }

                    }
                }
                // emitter 流正常结束
                emitter.complete();
                log.info("Stream completed - model: {}", modelName);
            } catch (Exception e) {
                log.error("Stream request failed - model: {}", modelName, e);
               emitter.completeWithError(e);
            }
        }).start();

        // 3. 直接返回 emitter，不需要等线程执行完
        return emitter;
    }
}