package com.kyon.llmgateway.agent.tool.builtin;

import com.kyon.llmgateway.agent.tool.Tool;
import com.kyon.llmgateway.agent.tool.ToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 抓取指定网页内容
 */
@ToolDef
@Component
public class WebFetchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);
    private static final ObjectMapper om = new ObjectMapper();
    private static final int MAX_LENGTH = 3000;

    // 定义客户端，10秒超时，自动跟随重定向
    HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "抓取指定网页的文本内容，返回前 " + MAX_LENGTH + " 个字符";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");

        // 参数 url，只要一个string
        ObjectNode url = properties.putObject("url");
        url.put("type", "string");
        url.put("description", "目标网页 URL，必须以 http:// 或 https:// 开头");

        params.putArray("required").add("url");
        return params;
    }

    @Override
    public String execute(JsonNode arguments) {
        String url = arguments.path("url").asString();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            log.warn("Invalid URL: {}, 错误：URL 必须以 http:// 或 https:// 开头", url);
            return "错误：URL 必须以 http:// 或 https:// 开头";
        }
        try {
            // 定义请求头
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (compatible; LLMGateway/1.0)")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            // 去除 HTML 标签，只留文本
            String text = body.replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s+", " ")
                    .trim();

            // 阶段3000个字符防止撑爆上下文
            if (text.length() > MAX_LENGTH) {
                text = text.substring(0, MAX_LENGTH) + "...(内容过长已截断)";
            }

            log.info("Fetch URL: {}, length: {}", url, text.length());
            return text;
        } catch (Exception e) {
            log.error("Failed to fetch URL: {} ", url, e);
            return "错误：抓取页面失败 - " + e.getMessage();
        }
    }
}
