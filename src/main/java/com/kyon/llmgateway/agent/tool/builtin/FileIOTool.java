package com.kyon.llmgateway.agent.tool.builtin;

import com.kyon.llmgateway.agent.tool.Tool;
import com.kyon.llmgateway.agent.tool.ToolDef;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${agent.workspace-dir}")
    private String workspaceDir;

    @Getter
    @Setter
    private Path basePath;      // 词法root：配置字符串解析成的绝对路径，只用来做字符串级别对比

    @Setter
    @Getter
    private Path realBasePath;  // 物理root：basePath跟随所有路牌后的真实位置，用于最终核对

    @PostConstruct
    void init() {
        Path base = Paths.get(workspaceDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(base);          // fail-fast：建不出来 -> 项目启动就报错
            realBasePath = base.toRealPath();   // 把路径上的路牌一路解到底，得到真实位置
        } catch (IOException e) {
            throw new IllegalStateException("workspace 目录不可用：" + base, e);
        }

        basePath = base;
        log.info("FileIO workspace = {}", base);
    }

    @Override
    public String getName() {
        return "file_io";
    }

    @Override
    public String getDescription() {
        return "读取或写入本地文件，安全目录为 " + workspaceDir + " 下";
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
        path.put("description", "文件名（仅文件名，不包含目录路径，如在 " + workspaceDir + "目录下)");

        ObjectNode content = properties.putObject("content");
        content.put("type", "string");
        content.put("description", "文件内容（仅在 action 为 \"write\" 时需要）");

        // content 仅在 write 时需要
        params.putArray("required").add("action").add("path");
        return params;
    }

    @Override
    public String execute(JsonNode arguments) throws IOException {
        String action = arguments.path("action").asString("");
        String fileName = arguments.path("path").asString("");

        // 第1层安全检查：文件名 字符黑名单（挡住 / \ .. 保证文件名字只有一层）
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return "错误：文件名包含非法字符，仅支持纯文件名";
        }

        Path targetPath = basePath.resolve(fileName).normalize();

        // 第2层：字符串级检查 -- 确认目标路径在安全目录 data/workspace/ 目录下操作
        if (!targetPath.startsWith(basePath.toString())) {
            return "错误：访问路径超出安全目录 " + workspaceDir;
        }

        try {
            // 第3层：真实磁盘检查
            // 3a. 目标文件本身是路牌吗，workspace用不到路牌，见到就拒
            if (Files.isSymbolicLink(targetPath)) return "错误: 目标文件是符号连接，已拒绝";

            // 3b. 父目录的真实位置还在沙箱里吗
            //     目前文件名只有一层 -> 父目录恒为 base -> 这行恒通过
            //     将来开放子目录(notes/a.txt) 时自动生效
            if (!targetPath.getParent().toRealPath().startsWith(realBasePath)) {
                return "错误: 真实路径超出安全目录";
            }

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

                // 父级目录不存在则创建(当前父目录恒为 base, 在init中已经创建,这是为将来子目录留的)
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
