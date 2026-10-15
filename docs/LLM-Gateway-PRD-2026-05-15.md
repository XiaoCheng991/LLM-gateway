# LLM API Gateway · 企业级多场景大模型 API 调用与能力测试平台

## 产品需求文档 (PRD)
> 版本：v1.0 | 2026-05-15

---

## 1. 产品概述

### 1.1 背景

企业使用大模型时，面临以下核心痛点：

- **多模型管理混乱**：同一业务可能需要对比 Nvidia / OpenRouter / Claude / 本地模型的效果，缺乏统一接入层
- **成本不可控**：无法追踪每个 prompt 的 token 消耗、响应时间、成本分摊
- **质量无保障**：模型升级后业务表现是否退化？不同 prompt 策略效果对比？缺少系统化的评测手段
- **安全性缺失**：API Key 分散在各系统、Prompt 注入无防护、敏感信息泄露风险
- **切换成本高**：更换模型提供商需修改业务代码，缺乏模型层面的抽象

### 1.2 目标

构建一个统一的企业级 LLM API Gateway，实现：

1. **统一接入**：对业务层屏蔽底层模型差异，提供一致的 OpenAI 兼容 API（Nvidia / OpenRouter 均兼容 OpenAI 格式）
2. **成本可观测**：全链路 token、延迟、费用追踪
3. **质量评测**：支持 Prompt A/B 测试、模型对比、回归测试
4. **安全保障**：API Key 集中管理 + Rate Limiting + Prompt 安全检测
5. **可扩展**：新增模型提供商只需增加适配器，不侵入现有逻辑

### 1.3 目标用户

| 用户角色 | 使用场景 |
|---------|---------|
| Java 后端开发 | 集成 LLM 能力到现有业务系统 |
| AI 应用开发者 | 测试不同模型/prompt 的效果对比 |
| 技术管理者 | 查看成本报表、质量趋势 |
| QA 工程师 | 回归测试、Prompt 效果验证 |

---

## 2. 功能需求

### 2.1 核心功能 (P0)

#### F1: 统一 Chat/Completion API

- `POST /api/chat/completions` — OpenAI 兼容格式，支持流式 (SSE) 和非流式
- `GET /api/v1/models` — 列出所有可用模型
- `POST /api/v1/embeddings` — embedding 向量生成

#### F2: 多模型适配器

| 适配器 | 状态 | 说明 |
|--------|------|------|
| Nvidia Adapter | ✅ MVP | 支持 DeepSeek-V4、Kimi-K2.6 等模型 |
| OpenRouter Adapter | ✅ MVP | 支持 Owl-Alpha、Nemotron 等模型 |
| Claude Adapter | 📋 规划 | Claude Sonnet, Opus |
| Ollama Adapter | 📋 规划 | 本地开源模型 (qwen2, llama3) |
| Azure OpenAI | 📋 规划 | 企业 Azure 环境 |

#### F3: Prompt 模板管理

- 创建/编辑/删除 Prompt 模板
- 模板参数化（`{{variable}}` 插值）
- 模板版本管理（每个更新保留历史版本）
- 系统 Prompt + 用户 Prompt 分层管理

#### F4: A/B 测试与模型对比

- 同一 prompt 发送到多个 Provider（如 Nvidia vs OpenRouter），并排对比响应
- 对比维度：响应内容、延迟、token 消耗、成本
- 支持手动触发和 API 触发

#### F5: 基础监控

- 请求量：时间维度聚合（分钟/小时/天）
- 响应延迟：P50 / P95 / P99 分位延迟
- Token 消耗：输入/输出 token 各维度统计
- 成本估算：基于各模型定价的实时费用
- 错误率：4xx/5xx 分布

### 2.2 增强功能 (P1)

#### F6: Prompt 评测 (Eval)

- 预置评测指标：响应长度、关键词匹配、格式验证
- LLM-as-Judge 评测：用另一个大模型评估输出质量
- 评测报告：对比不同模型/prompt/温度下的评分趋势

#### F7: Rate Limiting 与配额管理

- 按 API Key / 用户 / 模型维度限流
- Token 级别配额控制
- 超限时的降级策略（排队 / 拒绝 / 切换到备用模型）

#### F8: 安全模块

- API Key 自动轮转与加密存储
- Prompt 注入检测（基于规则或 LLM）
- PII / 敏感信息脱敏
- 审计日志：所有请求的完整链路追踪

### 2.3 扩展功能 (P2)

- Webhook 回调：异步任务完成后通知业务系统
- 缓存层：对幂等请求缓存响应（相同 prompt+参数）
- 负载均衡：多 provider 间自动故障转移
- 模型路由：基于 cost / latency / quality 的智能路由
- 批处理：批量请求聚合，减少 API 调用次数

---

## 3. 系统架构

### 3.1 架构总览

```
┌──────────────────────────────────────────────────────────────┐
│                        Client / SDK                          │
└────────────────────────┬─────────────────────────────────────┘
                         │ HTTP/SSE
                         ▼
┌──────────────────────────────────────────────────────────────┐
│                    API Gateway Layer                          │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────────────┐  │
│  │ Rate Limiter│  │ Auth Filter │  │ Request Validator    │  │
│  └─────────────┘  └─────────────┘  └──────────────────────┘  │
└────────────────────────────────┬─────────────────────────────┘
                                 ▼
┌──────────────────────────────────────────────────────────────┐
│                    Router / Orchestrator                      │
│  ┌──────────────────────────────────────────────────────┐    │
│  │  Model Selector → Fallback Chain → Load Balancer     │    │
│  └──────────────────────────────────────────────────────┘    │
└────────────────────────────────┬─────────────────────────────┘
                                 ▼
┌──────────────────────────────────────────────────────────────┐
│                    Adapter Layer (Strategy Pattern)          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐   │
│  │ Nvidia   │ │ OpenRouter │ │ Claude  │ │ Ollama  ...   │   │
│  │ Adapter  │ │ Adapter  │ │ Adapter │ │ Adapter       │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────┘   │
└────────────────────────────────┬─────────────────────────────┘
                                 ▼
┌──────────────────────────────────────────────────────────────┐
│                    External LLM APIs                          │
│  Nvidia    OpenRouter    Claude    Ollama    Azure OpenAI      │
└──────────────────────────────────────────────────────────────┘
                                                         
┌──────────────────────────────────────────────────────────────┐
│                    Observability Layer                        │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────────────┐  │
│  │  Metrics    │  │    Logs     │  │    Cost Tracker      │  │
│  └─────────────┘  └─────────────┘  └──────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 设计原则

1. **适配器模式**：LLMService 接口定义契约，各 Provider 实现 Adapter
2. **工厂模式**：LLMServiceFactory 根据 modelId 动态返回对应实现
3. **责任链模式**：请求处理管线 (Auth → Rate Limit → Validator → Router)
4. **OpenAI 兼容**：对外暴露和 OpenAI 一致的 API 格式（Nvidia / OpenRouter 均支持），降低客户端接入成本
5. **无状态设计**：Gateway 层无状态，水平扩展只加实例

### 3.3 数据流

```
Request Flow (同步 / 流式):

Client 
  → POST /api/v1/chat/completions { model, messages, stream }
  → Auth Filter (校验 API Key)
  → Rate Limiter (检查配额)
  → Request Validator (参数校验)
  → Model Selector (根据 model 选择适配器)
  → LLMService.send() or stream()
    → Adapter 转换请求格式
    → HTTP 调用外部 LLM API
    → Adapter 转换响应格式
  → Metrics Collector (记录延迟/token/成本)
  → Response (JSON / SSE stream)
```

---

## 4. 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 框架 | Spring Boot 3.x | Java 21 + WebFlux (流式支持) |
| API 文档 | SpringDoc OpenAPI | 自动生成 Swagger 文档 |
| 数据库 | H2 (dev) / PostgreSQL (prod) | 存储模板、配置、历史记录 |
| 缓存 | Caffeine (本地) / Redis | 模型配置缓存、限流计数器 |
| 流式 | Server-Sent Events (SSE) | 兼容 OpenAI 流式格式 |
| 评测 | LLM-as-Judge | 用模型评测模型 |
| 监控 | Micrometer + Prometheus | JVM 指标、请求指标 |
| 构建 | Maven | 依赖管理 |
| 测试 | JUnit 5 + Mockito + Testcontainers | 单元测试 + 集成测试 |

---

## 5. API 设计

### 5.1 Chat Completion

```http
POST /api/chat/completions
Content-Type: application/json

{
  "model": "nvidia/deepseek-ai/deepseek-v4-flash",
  "messages": [
    {"role": "system", "content": "你是一个专业的 Java 技术顾问，精通 Spring Boot 和微服务架构。"},
    {"role": "user", "content": "请解释 Spring Boot 自动配置的原理"}
  ],
  "temperature": 0.7,
  "max_tokens": 2048,
  "stream": false
}
```

响应：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "content": "Spring Boot 的自动配置通过 @EnableAutoConfiguration...",
    "model": "nvidia/deepseek-ai/deepseek-v4-flash",
    "inputTokens": 45,
    "outputTokens": 220,
    "latency": 2340
  }
}
```

### 5.2 模型列表

```http
GET /api/models
```

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {"model": "openrouter/owl-alpha", "provider": "openrouter", "status": "active"},
    {"model": "openrouter/nvidia/nemotron-3-super-120b-a12b:free", "provider": "openrouter", "status": "active"},
    {"model": "nvidia/deepseek-ai/deepseek-v4-flash", "provider": "nvidia", "status": "active"},
    {"model": "nvidia/deepseek-ai/deepseek-v4-pro", "provider": "nvidia", "status": "active"},
    {"model": "nvidia/moonshot-ai/kimi-k2.6", "provider": "nvidia", "status": "active"}
  ]
}
```

### 5.3 A/B 对比

```http
POST /api/v1/eval/compare
Authorization: Bearer <api-key>

{
  "prompt": "请用一句话解释微服务架构的优缺点",
  "models": [
    {"model": "gpt-4o", "temperature": 0.7},
    {"model": "deepseek-chat", "temperature": 0.7}
  ],
  "system_prompt": "你是一个软件架构师，回答需简洁、专业。"
}
```

---

## 6. 项目里程碑

### MVP (第 1-3 周)

| 阶段 | 时间 | 交付物 |
|------|------|--------|
| 项目骨架搭建 | Day 1-2 | Spring Boot 项目 + 目录结构 + 基础配置 |
| 核心接口定义 | Day 3-4 | LLMService 接口 + ChatRequest/Response 模型 |
| Nvidia 适配器 | Day 5-7 | Nvidia Adapter 实现 + Chat Completion 端点 |
| OpenRouter 适配器 | Day 8-10 | OpenRouter Adapter 实现 + 工厂模式整合 |
| 模型列表 API | Day 11 | GET /api/v1/models |
| 基础监控 | Day 12-13 | Metrics 采集 (延迟/token/成本) |
| 流式响应 | Day 14-15 | SSE 流式支持 |
| 测试 + 文档 | Day 16-17 | 单元测试 + API 文档 + 启动脚本 |
| **MVP 验收** | **Day 18-21** | **3 个核心 API 可用 + 2 个模型适配器 + 基础监控** |

### V1.0 (第 4-7 周)

- Prompt 模板管理
- A/B 对比测试
- Eval 评测系统
- Rate Limiting
- 安全模块

### V2.0 (第 8-12 周)

- Claude / Ollama 适配器
- 缓存层
- 负载均衡与故障转移
- Webhook 回调
- 管理后台 UI（可选）

---

## 7. 非功能性需求

| 维度 | 要求 | 度量方式 |
|------|------|---------|
| 性能 | P99 延迟 < 200ms（不含 LLM 调用） | Grafana + Micrometer |
| 可用性 | 99.9%（Gateway 层，不含下游 LLM） | 健康检查 + 熔断 |
| 吞吐 | 单实例 1000 QPS | 压测 (JMeter / k6) |
| 安全 | API Key 加密存储 + 传输加密 | 审计 + 渗透测试 |
| 可扩展 | 新增模型适配器 ≤ 1 人日 | 代码审查 |
| 可观测 | 全链路 trace ID 追踪 | 结构化日志 + Metrics |
| 兼容性 | OpenAI 兼容度 ≥ 90%（Nvidia / OpenRouter 均支持） | 兼容性测试套件 |

---

## 8. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| LLM API 不稳定 | 高 | 中 | 熔断 + 重试 + 备用模型 |
| 流式响应中断 | 中 | 高 | 自动重连 + 断点续传 |
| Token 成本失控 | 中 | 高 | 实时配额控制 + 预算告警 |
| Prompt 注入攻击 | 低 | 高 | 输入过滤 + 沙箱执行 |
| 模型版本升级影响 | 中 | 中 | Pin 模型版本 + 回归测试 |

---

## 9. 附录

### 9.1 参考项目

| 项目 | Star | 核心贡献 |
|------|------|---------|
| LiteLLM | 47k⭐ | Python Proxy Server，100+ LLM 统一接口 |
| Portkey Gateway | 11.7k⭐ | 轻量 AI Gateway，1600+ LLM 路由 |
| Helicone | 5.7k⭐ | LLM 可观测性平台 |
| Spring AI | — | Spring 生态官方 AI 集成框架 |

### 9.2 技术选型说明

本项目选择 **Java + Spring Boot** 而非 Python/Node 的原因：
1. 与你的 Java 后端技能栈无缝衔接
2. Spring Boot 成熟的生态（安全、监控、事务、AOP）
3. Java 21 虚拟线程对高并发 I/O 的原生支持
4. 企业级部署环境中 Java 的运维成熟度最高
5. 在 AI Gateway 市场形成差异化（目前主流方案均为 Python/Node）

### 9.3 术语表

| 术语 | 说明 |
|------|------|
| LLM | Large Language Model，大语言模型 |
| SSE | Server-Sent Events，服务端推送事件 |
| RAG | Retrieval-Augmented Generation，检索增强生成 |
| Prompt | 输入给模型的指令文本 |
| Token | 模型的最小计算单元（约 3/4 个英文单词） |
| Adapter | 适配器，封装不同 LLM 提供商的调用差异 |
| Eval | Evaluation，对模型输出的自动评测 |
| P50/P95/P99 | 响应延迟的分位值（50%/95%/99% 请求在此值以下） |