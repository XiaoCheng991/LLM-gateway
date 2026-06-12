-- 创建 消息表
CREATE TABLE IF NOT EXISTS messages (
                                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                                        session_id TEXT NOT NULL,
                                        role TEXT NOT NULL,
                                        content TEXT,
                                        tool_calls TEXT,
                                        tool_call_id TEXT,
                                        name TEXT,
                                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 建立对应索引
CREATE INDEX IF NOT EXISTS idx_session_id ON messages(session_id);