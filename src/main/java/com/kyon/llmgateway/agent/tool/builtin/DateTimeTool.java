package com.kyon.llmgateway.agent.tool.builtin;

import com.kyon.llmgateway.agent.tool.Tool;
import com.kyon.llmgateway.agent.tool.ToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@ToolDef
@Component
public class DateTimeTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(DateTimeTool.class);
    private static final ObjectMapper om = new ObjectMapper();

    @Override
    public String getName() {
        return "datetime";
    }

    @Override
    public String getDescription() {
        return "获取当前日期和时间，支持自定义格式";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");
        ObjectNode format = properties.putObject("format");
        format.put("type", "string");
        format.put("description", "日期时间格式，默认 yyyy-MM-dd HH:mm:ss, 如yyyy-MM-dd、HH:mm:ss");

        params.putArray("required"); // format 非必填
        return params;
    }

    @Override
    public String execute(JsonNode arguments) {
        String format = "yyyy-MM-dd HH:mm:ss";

        // 参数检查
        if (arguments.has("format") && !arguments.get("format").isNull()) {
            format = arguments.get("format").asString();
        }

        try {
            String result = LocalDateTime.now().format(DateTimeFormatter.ofPattern(format));
            log.info("DateTime: {}", result);
            return result;
        } catch (IllegalArgumentException e) {
            // 格式不合法时会推到日期
            log.warn("Invalid date format: {}, falling back to date", format);
            return LocalDate.now().toString();
        }
    }
}
