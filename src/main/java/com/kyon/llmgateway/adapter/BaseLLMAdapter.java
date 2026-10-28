package com.kyon.llmgateway.adapter;

import com.kyon.llmgateway.agent.engine.TokenCounter;
import com.kyon.llmgateway.model.ChatRequest;
import com.kyon.llmgateway.model.ChatResponse;
import com.kyon.llmgateway.model.Message;
import com.kyon.llmgateway.model.ToolDefinition;
import com.kyon.llmgateway.observability.RateLimitExceededException;
import com.kyon.llmgateway.observability.TokenBucketRateLimiter;
import com.kyon.llmgateway.observability.UpstreamHttpException;
import com.kyon.llmgateway.service.LLMService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public abstract class BaseLLMAdapter implements LLMService {

    private static final Logger log = LoggerFactory.getLogger(BaseLLMAdapter.class);

    @Resource
    private TokenBucketRateLimiter tokenBucketRateLimiter;

    @Value("${llm.sse-timeout:300000}") // 默认5分钟
    private long sseTimeout;

    // 模型名按请求显式传参
    protected String getEffectiveModel(String model) {
        return model;
    }

    // 子类仅需要提供三个配置
    protected abstract String getBaseUrl();

    protected abstract String getApiKey();

    public abstract String getProviderName();

    // ObjectMapper
    protected final ObjectMapper om = new ObjectMapper();
    protected static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();

    // 线程池 复用(虚拟线程)
    // newCachedThreadPool 适合SSE这种长连接、数量不固定
    protected final ExecutorService executor = Executors.newCachedThreadPool(
            Thread.ofVirtual().name("sse-stream-", 0).factory()
    );

    @Override
    public ChatResponse chat(String model, List<Message> userMsgList, List<ToolDefinition> tools) throws Exception {
        // 工具存在时默认 “auto”，否则不发 tool_choice 字段
        JsonNode toolChoice = (tools == null || tools.isEmpty()) ? null : om.valueToTree("auto");
        return chat(model, userMsgList, tools, toolChoice);
    }

    /**
     * 普通 Loop 调用请求
     */
    @Override
    public ChatResponse chat(String model, List<Message> userMsgList, List<ToolDefinition> tools, JsonNode toolChoice) throws Exception {
        String modelName = getEffectiveModel(model);
        log.debug("Chat request - model: {}, messages: {}", modelName, userMsgList.size());

        // 0. 非消耗性预检：看桶里“现在”够不够，但不扣token
        if (!tokenBucketRateLimiter.tryAcquireNonDestructive(getProviderName())) {
            throw new RateLimitExceededException(
                    getProviderName(),
                    tokenBucketRateLimiter.getMaxTokensForProvider(getProviderName())
            );
        }

        // 1. 拼 JSON 请求体
        ChatRequest req = new ChatRequest(modelName, userMsgList, false, tools, toolChoice);
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
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        long latency = System.currentTimeMillis() - start;

        // 上游 HTTP 层错误（4xx/5xx）：错误体里没有 choices
        if (response.statusCode() >= 400) {
            throw new UpstreamHttpException(response.statusCode(), response.body());
        }

        // 4. 解析响应, 解析为ChatResponse
        JsonNode root = om.readTree(response.body());

        // 校验错误
        if (root.has("error") && !root.has("choices")) {
            throw new RuntimeException("API Error: " + root.path("error")
                    .path("message").asString("unknown error"));
        }

        JsonNode messageNode = root.path("choices").path(0).path("message");
        String content = messageNode.path("content").asString("");
        // 思考内容：DeepSeek 原生风格 reasoning_content 优先，OpenRouter 归一化字段 reasoning 兜底
        String reasoning = messageNode.path("reasoning_content").asString("");
        if (reasoning.isEmpty()) reasoning = messageNode.path("reasoning").asString("");

        Integer inputTokens = root.path("usage").path("prompt_tokens").asInt(0);
        Integer outputTokens = root.path("usage").path("completion_tokens").asInt(0);
        String respModel = root.path("model").asString(model);
        // 获取工具相关
        String finishReason = root.path("choices").path(0).path("finish_reason").asString("");

        // 解析 tool_calls
        JsonNode toolCallsNode = null;

        if (messageNode.has("tool_calls") && !messageNode.path("tool_calls").isNull()) {
            toolCallsNode = messageNode.path("tool_calls");
        }

        log.info("Chat completed - model: {}, inputTokens: {}, outputTokens: {}, latency: {}ms",
                respModel, inputTokens, outputTokens, latency);

        // 5. 扣减 Token (Http 成功后才口 token，重试场景节省配额)
        tokenBucketRateLimiter.recordSuccess(getProviderName());

        return ChatResponse.builder()
                .content(content)
                .model(respModel)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .estimated(false)
                .latency(latency)     // 单位：毫秒
                .finishReason(finishReason)
                .toolCalls(toolCallsNode) // 直接传递 tool_calls 的 JsonNode
                .reasoningContent(reasoning)
                .build();
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        log.info("Shutting down ExecutorService for {}", getProviderName());
        if (!executor.isShutdown()) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("Executor did not terminate in 10s, forcing shutdown");
                    executor.shutdown();
                }
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for ExecutorService to terminate", e);
                executor.shutdown();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * OpenAI 兼容 SSE 流式转发。
     * 帧格式：data: {"id":"chatcmpl-..","object":"chat.completion.chunk","created":..,
     * "model":"..","choices":[{"index":0,"delta":{...},"finish_reason":null}]}
     * 结尾：   finish_reason 帧 → usage 帧 → data: [DONE]
     * 不执行 tool loop：上游返回的 delta.tool_calls 原样透传给客户端。
     */
    @Override
    public SseEmitter stream(String model, List<Message> userMsgList, List<ToolDefinition> tools) {
        return stream(model, userMsgList, tools, null);
    }

    /**
     * 流式转发并显示指定 tool_choice: 客户端给了就照办("none"/指定函数)
     * 没给维持旧默认（tools非空 -> auto）
     */
    @Override
    public SseEmitter stream(String model, List<Message> userMsgList, List<ToolDefinition> tools, JsonNode toolChoice) {
        String modelName = getEffectiveModel(model);
        log.debug("Stream request - model: {}, messages: {}", modelName, userMsgList.size());

        // 1. 创建 SseEmitter，设置 5 分钟超时
        SseEmitter emitter = new SseEmitter(sseTimeout);

        // 客户端断开/超时/出错 -> 置位，读取循环据此提前退出并关闭上游连接
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));  // 三回调 -> 置位
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError((e) -> cancelled.set(true));

        // 2. 启动新线程执行流式请求
        executor.submit(() -> {
            String chunkId = "chatcmpl-" + UUID.randomUUID();
            long created = System.currentTimeMillis() / 1000;
            int promptTokens = 0;
            int completionTokens = 0;
            String finishReason = null;
            boolean sawUsage = false;

            try {
                // 2.1. 发起 HTTP POST 请求，请求体里加 "stream": true
                // 显示 tool_choice 优先：未给时维持旧默认（tools非空 -> auto）
                JsonNode effectiveToolChoice = (toolChoice != null && !toolChoice.isNull())
                        ? toolChoice
                        : (tools == null || tools.isEmpty()) ? null : om.valueToTree("auto");
                ChatRequest req = new ChatRequest(modelName, userMsgList, true, tools, effectiveToolChoice);
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
                HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());

                // 上游 HTTP 层错误（4xx/5xx）
                if (response.statusCode() >= 400) {
                    String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    emitter.send(SseEmitter.event().data(errorChunk(chunkId, modelName, created, errBody)));
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                    return;
                }

                promptTokens = TokenCounter.estimateMessages(userMsgList);

                // 2.4 逐行读取输入流
                try (BufferedReader br = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    // 2.5 每行以 "data: " 开头时，提取后面的 JSON 字符串
                    String line;
                    while ((line = br.readLine()) != null) {
                        // 客户端已经断开：return 会触发 try-with-resources 关闭
                        if (cancelled.get()) {
                            log.info("Client disconnected, closing upstream - model: {}", modelName);
                            return;
                        }

                        if (!line.startsWith("data: ")) continue;

                        // 读到 "data: [DONE]" 时调用 emitter.complete() 结束
                        String data = line.substring(6);
                        if ("[DONE]".equals(data)) break;

                        JsonNode root;
                        try {
                            // 解析 JSON 拿到content，通过 emitter.send() 推给客户端
                            root = om.readTree(data);
                        } catch (Exception parseErr) {
                            // JSON 解析失败，继续下一行，不要中断流
                            // 可以日志记录，但不要打断推送, 跳过无法解析的行，不影响后续推送
                            log.warn("【SSE】parse error, skipping line: {}", line, parseErr);
                            continue;
                        }

                        // 处理调用错误
                        if (root.has("error")) {
                            String errMsg = root.path("error").path("message").asString("unknown error");
                            emitter.send(SseEmitter.event().data(errorChunk(chunkId, modelName, created, errMsg)));
                            emitter.send(SseEmitter.event().data("[DONE]"));
                            emitter.complete();
                            return;
                        }

                        // 上游给真实 usage 就用真实值 -- 必先于 choices 判空
                        // 规范允许独立 usage 帧(choices:[]), 先判空会把真实计费值跳过
                        JsonNode usageNode = root.path("usage");
                        if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                            promptTokens = usageNode.path("prompt_tokens").asInt(promptTokens);
                            completionTokens = usageNode.path("completion_tokens").asInt(completionTokens);
                        }

                        JsonNode choices = root.path("choices");
                        if (!choices.isArray() || choices.isEmpty()) continue;
                        JsonNode choice = choices.get(0);

                        JsonNode fr = choice.path("finish_reason");
                        if (!fr.isMissingNode() && !fr.isNull()) finishReason = fr.asString();

                        JsonNode delta = choice.path("delta");
                        if (delta.isMissingNode() || delta.isNull() || delta.isEmpty()) continue;

                        // content 存在则累加估算 token：tool_choices 原样透传
                        String piece = delta.path("content").asString("");
                        if (!piece.isEmpty()) completionTokens += TokenCounter.estimateTokens(piece);

                        emitter.send(SseEmitter.event().data(chunk(chunkId, modelName, created, delta, finishReason)));
                    }
                }

                if (finishReason == null) finishReason = "stop";

                // 结束帧：finish_reason（delta 为空对象）
                emitter.send(SseEmitter.event().data(
                        chunk(chunkId, modelName, created, om.createObjectNode(), finishReason)));
                // usage 帧（sawUsage=false 时 completionTokens 已由 content 累加得出）
                emitter.send(SseEmitter.event().data(
                        usageChunk(chunkId, modelName, created, promptTokens, completionTokens)));
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();

            } catch (Exception e) {
                // 客户端断开属于正常收尾 -- 不记 ERROR、不回推错误帧（响应已断）
                if (cancelled.get() || isClientDisconnect(e)) {
                    log.info("Client disconnected, closing upstream - model: {}", modelName);
                    return;
                }

                log.error("Stream request failed - model: {}", modelName, e);
                try {
                    emitter.send(SseEmitter.event().data(errorChunk(chunkId, modelName, created, e.getMessage())));
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                } catch (Exception ignore) {
                }
            }
        });

        // 3. 直接返回 emitter，不需要等线程执行完
        return emitter;
    }

    /**
     * 流式调用 LLM，同时累及完整响应，用于 Tool Loop
     *
     * @param messages       对话历史
     * @param tools          工具定义
     * @param onContentDelta 每个 content delta 的回调，推给前端
     * @return 完整的 ChatResponse（含 finishReason 和 toolCalls）
     */
    public ChatResponse chatStreaming(String model, List<Message> messages, List<ToolDefinition> tools,
                                      Consumer<String> onContentDelta, Consumer<String> onReasoningDelta) throws Exception {
        String modelName = getEffectiveModel(model);
        log.debug("Streaming chat request -  model: {}, messages: {}", modelName, messages.size());
        // 注意：流式接口暂不进入限流
        // 业界惯例：SSE 长连接不适合令牌桶模型（连接持有时间长，扣Token时机不明确）
        // 本项目流式 Token 计量也是估算
        // 任何流式配额保护应在 AgentController 用 Semaphore 做并发数上限，而不是这里

        // 1. 构建请求
        JsonNode toolChoice = (tools == null || tools.isEmpty()) ? null : om.valueToTree("auto");
        ChatRequest req = new ChatRequest(modelName, messages, true, tools, toolChoice);
        String json = om.writeValueAsString(req);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer %s".formatted(getApiKey()))
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        // 2. 发请求，拿流式响应
        long start = System.currentTimeMillis();

        HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());

        // 上游 4xx/5xx 回的是 JSON 错误体而非 SSE
        if (response.statusCode() >= 400) {
            String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new UpstreamHttpException(response.statusCode(), errBody);
        }

        // 3. 逐行读，累计 content
        try (BufferedReader br = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            StringBuilder fullContent = new StringBuilder();
            StringBuilder fullReasoning = new StringBuilder();

            // 累计 tool_calls: key=index, value=拼接中的JSON
            Map<Integer, StringBuilder> toolCallArgs = new HashMap<>();
            Map<Integer, String> toolCallIds = new HashMap<>();
            Map<Integer, String> toolCallNames = new HashMap<>();
            String finishReason = "";

            String line;
            while ((line = br.readLine()) != null) {        // readLine 没数据就阻塞等待
                if (!line.startsWith("data: ")) continue;   // 跳过注释/空行

                String data = line.substring(6);
                if ("[DONE]".equals(data)) break;           // 上游说结束

                try {
                    JsonNode root = om.readTree(data);

                    // 错误处理
                    if (root.has("error")) {
                        String errMsg = root.path("error").path("message").asString("unknown error");
                        throw new RuntimeException("API Error: %s".formatted(errMsg));
                    }
                    // 累计 delta content
                    // delta 就是增量，每次 SSE chunk 只包含当前新增的那一小块
                    JsonNode delta = root.path("choices").path(0).path("delta");
                    if (delta.isMissingNode() || delta.isNull()) continue;
                    if (delta.has("content")) {
                        JsonNode contentNode = delta.path("content");
                        String content = contentNode.asString();
                        if (!content.isEmpty()) {

                            // ===== Core 重点 =====
                            fullContent.append(content);    // 存一份
                            onContentDelta.accept(content); // 通道 A：数据出口一（推给前端）
                        }
                    }

                    // 累计 reasoning（OpenRouter: delta.reasoning; DeepSeek原生：delta.reasoning_content）
                    String reasoningPiece = delta.path("reasoning_content").asString("");
                    if (reasoningPiece.isEmpty()) {
                        reasoningPiece = delta.path("reasoning").asString("");
                    }
                    if (!reasoningPiece.isEmpty()) {
                        fullReasoning.append(reasoningPiece);
                        onReasoningDelta.accept(reasoningPiece); // 思考量推到前端
                    }

                    // 累计 tool_calls
                    if (delta.has("tool_calls")) {
                        for (JsonNode tc : delta.path("tool_calls")) {
                            int index = tc.path("index").asInt();
                            if (tc.has("id")) {
                                toolCallIds.put(index, tc.path("id").asString());
                            }
                            if (tc.has("function") && tc.path("function").has("name")) {
                                toolCallNames.put(index, tc.path("function").path("name").asString());
                            }
                            // 文本碎片必须取“原文”(asString), toString()会再包一层引号+转义, {"e -> {\"e
                            if (tc.has("function") && tc.path("function").has("arguments")) {
                                String args = tc.path("function").path("arguments").asString();
                                toolCallArgs.computeIfAbsent(index, k -> new StringBuilder()).append(args);
                            }
                        }
                    }

                    // finish_reason
                    JsonNode frNode = root.path("choices").path(0).path("finish_reason");
                    if (frNode != null && !frNode.isNull()) {
                        finishReason = frNode.asString();
                    }
                } catch (Exception parseErr) {
                    log.warn("【SSE】parse error, skipping line: {}", line, parseErr);
                }
                log.debug("SSE line parsed, delta={}", data);
            }
            long latency = System.currentTimeMillis() - start;

            // 组装 tool_calls (结构化构建，不再用字符串模版拼 JSON--拼接对内容零容忍)
            JsonNode toolCallsNode = null;
            if (!toolCallIds.isEmpty()) {
                ArrayNode toolCalls = om.createArrayNode();
                for (int i = 0; i < toolCallIds.size(); i++) {
                    String id = toolCallIds.get(i);
                    String name = toolCallNames.get(i);
                    String args = toolCallArgs.getOrDefault(i, new StringBuilder()).toString();

                    // 组装成 OpenAI tool_calls 格式
                    ObjectNode tcJson = om.createObjectNode();
                    tcJson.put("id", id);
                    tcJson.put("type", "function");
                    ObjectNode fn = tcJson.putObject("function");
                    fn.put("name", name);
                    try {
                        om.readTree(args.isEmpty() ? "{}" : args); // 先尝试解析，确保是合法 JSON
                        fn.put("arguments", args.isEmpty() ? "{}" : args);
                    } catch (Exception e) {
                        // 模型给的参数本身不是合法 JSON：组装不炸，降级为空参数
                        log.warn("tool_call arguments 非法 JSON，降级为空参数, name: {}, args: {}", name, args, e);
                        fn.put("arguments", "{}");  // 降级为空对象
                    }
                    toolCalls.add(tcJson);
                }
                toolCallsNode = toolCalls;
            }

            // token 估算（流式响应没有 usage，用message和token估算）
            int inputTokens = TokenCounter.estimateMessages(messages);
            int outputTokens = TokenCounter.estimateTokens(fullContent.toString()); // 用通道 A 时存的那份

            return ChatResponse.builder()
                    .content(fullContent.toString())    // 通道 B：数据出口二（stream完成后攒够了一次性返回交给程序决策）
                    .model(modelName)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .estimated(true)
                    .latency(latency)
                    .finishReason(finishReason)
                    .toolCalls(toolCallsNode)
                    .reasoningContent(fullReasoning.toString())
                    .build();
        } catch (Exception e) {
            log.error("Stream request failed - model: {}", modelName, e);
            throw e;
        }
    }

    // --------- SSE 帧构造（OpenAI chat.completion.chunk 格式）---------

    /**
     * 普通帧：content / tool_calls 透传，或结束时的 finish_reason
     */
    private String chunk(String id, String model, long created, JsonNode delta, String finishReason) {
        try {
            ObjectNode root = om.createObjectNode();
            root.put("id", id);
            root.put("object", "chat.completion.chunk");
            root.put("created", created);
            root.put("model", model);
            ObjectNode choice = root.putArray("choices").addObject();
            choice.put("index", 0);
            choice.set("delta", delta);
            if (finishReason == null) choice.putNull("finish_reason");
            else choice.put("finish_reason", finishReason);
            return om.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build chunk", e);
        }
    }

    /**
     * usage 帧：choices 为空数组，OpenAI规范
     */
    private String usageChunk(String id, String model, long created, int promptTokens, int completionTokens) {
        try {
            ObjectNode root = om.createObjectNode();
            root.put("id", id);
            root.put("object", "chat.completion.chunk");
            root.put("created", created);
            root.put("model", model);
            root.putArray("choices");       // 空数组
            ObjectNode usage = root.putObject("usage");
            usage.put("prompt_tokens", promptTokens);
            usage.put("completion_tokens", completionTokens);
            usage.put("total_tokens", promptTokens + completionTokens);
            return om.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build usage chunk", e);
        }
    }

    /**
     * 错误帧
     */
    private String errorChunk(String id, String model, long created, String message) {
        try {
            ObjectNode root = om.createObjectNode();
            ObjectNode err = root.putObject("error");
            err.put("message", message == null ? "unknow error" : message);
            err.put("type", "server_error");
            root.put("id", id);
            root.put("model", model);
            root.put("created", created);
            return om.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":{\"message\":\"serialization failed\"}}";
        }
    }

    /**
     * 判定异常是否源于客户端主动断开连接
     * Tomcat 抛 ClientAbortException、Spring 抛 AsyncRequestNotUsableException --
     * 二者是客户端侧专属条件；刻意不匹配笼统 IOException，避免把上游读取故障误吞。
     */
    private boolean isClientDisconnect(Throwable e) {
        for (Throwable t = e; t != null; t = (t == t.getCause() ? null : t.getCause())) {
            String name = t.getClass().getName();
            if (name.endsWith("ClientAbortException") || name.endsWith("AsyncRequestNotUsableException")) {
                return true;
            }
        }
        return false;
    }


}
