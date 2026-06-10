package com.kyon.llmgateway.agent.tool.builtin;

import com.kyon.llmgateway.agent.tool.Tool;
import com.kyon.llmgateway.agent.tool.ToolDef;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 根据 DuckDuckGo 网络搜索
 */
@ToolDef
@Component
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final ObjectMapper om = new ObjectMapper();

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "搜索互联网获取实时信息，支持指定返回条数";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");

        // query 查询参数中：string、integer
        ObjectNode query = properties.putObject("query");
        query.put("type", "string");
        query.put("description", "搜索关键词");

        ObjectNode maxResults = properties.putObject("max_results");
        maxResults.put("type", "integer");
        maxResults.put("description", "返回结果条数，默认 5，最大 10");

        params.putArray("required").add("query").add(maxResults);
        return params;
    }

    /**
     * 用 jsoup 模拟浏览器请求 DuckDuckGo 的 HTML 版搜索页
     */
    @Override
    public String execute(JsonNode arguments) {
        String query = arguments.get("query").asString();
        int maxResults = arguments.has("max_results") ? arguments.get("max_results").asInt(5) : 5;
        if (maxResults > 10) maxResults = 10;
        if (maxResults < 1) maxResults = 1;

        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://html.duckduckgo.com/html/?q=" + encoded;

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; LLMGateway/1.0)")
                    .timeout(80000)
                    .get();

            Elements results = doc.select(".result__body");

            StringBuilder sb = new StringBuilder();
            sb.append("搜索 \"").append(query).append("\" 的结果：\n\n");

            int count = 0;
            for (Element result : results) {
                if (count >= maxResults) break;

                // 解析 .result__body 中的标题、链接和摘要
                String title = result.select(".result__title").text();
                String snippet = result.select(".result__snippet").text();

                // a 元素链接
                Element aEle = result.select("a").first();
                String link = aEle != null ? aEle.attr("href") : "";

                // DuckDuckGo 的链接是重定向链接，需要提取真实 URL
                if (link.startsWith("//")) {
                    link = "https:" + link;
                }

                sb.append(++count).append(". ").append(title).append("\n");
                sb.append("    链接: ").append(link).append("\n");
                sb.append("    摘要: ").append(snippet).append("\n\n");
            }

            if (count == 0) {
                sb.append("未找到相关结果");
            }

            log.info("Searched: {}, results: {}", query, count);
            return sb.toString();

        } catch (Exception e) {
            log.error("Search failed: {}", query, e);
            return "错误：搜索失败 - " + e.getMessage();
        }
    }
}
