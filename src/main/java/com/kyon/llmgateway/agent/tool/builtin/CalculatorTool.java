package com.kyon.llmgateway.agent.tool.builtin;

import com.kyon.llmgateway.agent.tool.Tool;
import com.kyon.llmgateway.agent.tool.ToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.script.*;

@ToolDef
@Component
public class CalculatorTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(CalculatorTool.class);
    private static final ObjectMapper om = new ObjectMapper();
    private final ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public String getDescription() {
        return "执行数学四则运算, 支持加(+)、减(-)、乘(*)、除(/), 如 1+2 或 3*4";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");
        ObjectNode expression = properties.putObject("expression");
        expression.put("type", "string");
        expression.put("description", "数学表达式，如 1+2、3*4、10/2");

        params.putArray("required").add("expression");
        return params;
    }

    @Override
    public String execute(JsonNode arguments) {
        String expr = arguments.get("expression").asString();

        // 安全检查：只允许数字和运算符
        if (!expr.matches("[0-9+\\-*/().\\s]+")) {
            return "错误：表达式包含非法字符，仅支持数字和 + - * / ( )";
        }

        try {
            // 判空
            if (engine != null) {
                Object result = engine.eval(expr);
                log.info("Calculated: {} = {}", expr, result);
                return expr + " = " + result;
            }
        } catch (ScriptException e) {
            log.error("Calculation error : {}", e.getMessage());
            return "错误：无法计算表达式，" + e.getMessage();
        }
        // 引擎不可用，回退到简单实现
        return "错误：未安装脚本引擎，请添加对应依赖";
    }


}