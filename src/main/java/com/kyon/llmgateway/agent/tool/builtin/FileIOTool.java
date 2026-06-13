package com.kyon.llmgateway.agent.tool.builtin;

import com.kyon.llmgateway.agent.tool.Tool;
import com.kyon.llmgateway.agent.tool.ToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件读/写工具
 */
@ToolDef
@Component
public class FileIOTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileIOTool.class);
    private static final ObjectMapper om = new ObjectMapper();
    private static final String WORKSPACE_DIR = "data/workspace";

    @Override
    public String getName() {
        return "file_io";
    }

    @Override
    public String getDescription() {
        return "读取或写入本地文件，安全目录为 " + WORKSPACE_DIR + " 下";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");

        // query 查询参数中：string、integer、string
        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.put("description", "操作类型：\"read\" 读取文件, \"write\" 写入文件");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "文件名（仅文件名，不包含目录路径，如在 " + WORKSPACE_DIR + "目录下)");

        ObjectNode content = properties.putObject("content");
        content.put("type", "string");
        content.put("description", "文件内容（仅在 action 为 \"write\" 时需要）");

        params.putArray("required").add("action").add("path").add("content");
        return params;
    }

    @Override
    public String execute(JsonNode arguments) {
        String action = arguments.path("action").asString();
        String fileName = arguments.path("path").asString();

        // 安全检查：防止路径穿越
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return "错误：文件名包含非法字符，仅支持纯文件名";
        }

        Path basePath = Paths.get(WORKSPACE_DIR).toAbsolutePath().normalize();
        Path targetPath = basePath.resolve(fileName).normalize();

        // 确认目标路径在安全目录 data/workspace/ 目录下操作
        if (!targetPath.startsWith(basePath.toString())) {
            return "错误：访问路径超出安全目录 " + WORKSPACE_DIR;
        }

        try {
            if ("read".equals(action)) {
                // 检查文件是否存在
                if (!Files.exists(targetPath)) {
                    return "错误：文件不存在 - " + fileName;
                }

                String content = Files.readString(targetPath);
                log.info("Read file: {}, size: {}", fileName, content.length());
                return content;
            } else if ("write".equals(action)) {
                String content = arguments.path("content").asString("");

                // 写入时自动创建子目录
                Files.createDirectories(targetPath.getParent());

                // 写入文件
                Files.writeString(targetPath, content);
                log.info("Write file: {}, size: {}", fileName, content.length());
                return "写入成功: 文件已保存到 " + targetPath + "，共 " + content.length() + " 字符";
            } else {
                return "错误： 不支持的操作类型 " + action + ", 仅支持 \"read\" 或 \"write\"";
            }
        } catch (IOException e) {
            log.error("File I/O error: {}", action, e);
            return "错误：文件操作失败 - " + e.getMessage();
        }
    }
}
