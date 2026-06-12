package com.kyon.llmgateway.agent.session;

import com.kyon.llmgateway.model.Message;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 是单文件数据库，并发写会锁整个文件
 */
@Component
public class SQLiteMessageStore implements MessageStore {

    private static final Logger log = LoggerFactory.getLogger(SQLiteMessageStore.class);

    @Value("${agent.db.url}")
    private String dbUrl;

    private final ObjectMapper objectMapper;

    public SQLiteMessageStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
              CREATE TABLE IF NOT EXISTS messages (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  session_id TEXT NOT NULL,
                  role TEXT NOT NULL,
                  content TEXT,
                  tool_calls TEXT,
                  tool_call_id TEXT,
                  name TEXT,
                  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
              )
          """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages(session_id)");
            log.info("SQLite messages 表初始化完成");
        } catch (Exception e) {
            log.error("SQLite 初始化失败", e);
        }
    }

    /**
     * 同步方法进行保存
     */
    @Override
    public synchronized void save(String sessionId, Message message) {
        String sql = "INSERT INTO messages (session_id, role, content, tool_calls, tool_call_id, name) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, message.getRole());
            ps.setString(3, message.getContent());
            ps.setString(4, message.getToolCalls() != null ? message.getToolCalls().toString() : null);
            ps.setString(5, message.getToolCallId());
            ps.setString(6, message.getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("保存消息失败", e);
        }
    }

    /**
     * 加载 SQLite
     */
    @Override
    public List<Message> load(String sessionId) {
        String sql = "SELECT role, content, tool_calls, tool_call_id, name FROM messages WHERE session_id = ? Order BY created_at ASC";
        List<Message> messages = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(dbUrl);
            PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);

            // 执行查询，读取每一行数据
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Message message = new Message();
                message.setRole(rs.getString("role"));
                message.setContent(rs.getString("content"));
                String toolCallJson = rs.getString("tool_calls");

                // 工具调用可能为空
                if (toolCallJson != null) {
                    message.setToolCalls(objectMapper.readTree(toolCallJson));
                }
                message.setToolCallId(rs.getString("tool_call_id"));
                message.setName(rs.getString("name"));
                messages.add(message);
            }
        } catch (Exception e) {
            throw new RuntimeException("加载消息失败", e);
        }
        return messages;
    }

    /**
     * 根据 sessionId 删除对应会话 (同步方法)
     */
    @Override
    public synchronized void delete(String sessionId) {
        String sql = "DELETE FROM messages WHERE session_id = ?";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除消息失败", e);
        }
    }
}
