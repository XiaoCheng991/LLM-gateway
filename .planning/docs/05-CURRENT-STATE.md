# 当前状态（2026-10-16 晚实测）

**验证方式:** 编译 + 起服务 + curl 三 Provider + grep/字节码检查（非推测）

---

## 一、进度

| 任务 | 状态 | 证据 |
|---|---|---|
| T0 Git 整理 | ✅ 已提交 | `874e5c0` + merge `7c2fd85` |
| T1 `estimated` 字段 | ✅ 已提交 | `ba7737a` |
| T2 `tool_choice` 字段名 | ✅ 已提交 | `e37000b` |
| T3 `LLMService` 重载 + `stream()` 加 tools | ✅ 已提交 | `31e420d` |
| Adapter 重构（3 bug + 方案1） | ✅ 已提交 `b6a3145`（已推送） | 见 04-ADAPTER-REFACTOR 第五节 |
| T4 `stream()` 重写 OpenAI chunk 格式 | ✅ 已提交 `2ffc420`（已推送） | ⚠️ **勘误**：该提交信息误写为 `fix(config)`，实际内容是 T4 的 BaseLLMAdapter 216 行（stage 时与 yml 串了）。已推送不重写历史，以本条为准 |
| yml OpenRouter 换 cohere 模型 + Maven Wrapper | ⏳ 待提交 | 工作区仅剩这两项 |
| T5-T8（SSE 收尾） | ⏸ 挂起 | 06-01-PLAN |
| Eval（06-02 T1-T12） | ⏸ 未开始 | — |
| `.planning/docs/` 文档 | ✅ 本目录 5 份 | 2026-10-16 |
| 整体复习 | 🔄 进行中 | 直到完全搞明白才回 T4 验证 |

**工作区未提交内容**（需拆成原子提交）：
1. Adapter 重构（adapter 包 + yml）→ `refactor(adapter)`
2. T4 的 `BaseLLMAdapter` 改动 → `feat(sse)`
3. `AgentController`/`ChatController` 注释微调 → `docs` 或并入 2
4. 未跟踪 `.gitattributes`/`.mvn/`/`mvnw`/`mvnw.cmd` → `chore`
5. `.planning/docs/` 5 份 → `docs(planning)`

> ⚠️ T4 为何在工作区：任务曾被暂停（先理清 Adapter 再回来），
> 但工作区里已有完整 T4 实现（3 个 chunk helper + cancelled 断开检测 + 4 处 `chat.completion.chunk`）。
> 归属与提交时机需确认后再动。

---

## 二、已知问题清单

### 🔴 P0：`BaseLLMAdapter.chat()` 不检查 HTTP 状态码
- **实证**：NVIDIA 上游返回 `410 Gone`（模型已 EOL），`chat()` 只找 body 里的
  `error` 字段，410 的 `{"type":"about:blank",...}` 没有该字段 → 落到 `choices` 缺失
  → 返回 `content:""` 且 HTTP 200"成功"
- **这就是长期"空 content"之谜的根因**（此前误判为 ReAct 不产出）
- 对照：流式路径 L227 有 `statusCode() >= 400` 检查——**同步路径漏了**
- 修法（学习模式，由你动手）：`chat()` 拿到响应后先判
  `response.statusCode() >= 400`，抛出带状态码的异常（参考 Phase 4 审查建议的 `LLMHttpException`）

### 🟠 P1：Agnes base-url 与 .env 不一致
- yml 硬编码 `https://apihub.agnes-ai.com/v1/chat/completions` → 用该 key 报 `Invalid token`
- `.env` 里 `AGNES_BASE_URL=https://api.agnes-ai.cn/v1`，**yml 完全没引用**（`*_BASE_URL` 三个都没引用）
- 实测用 `.env` 的 URL 直连：鉴权**通过**（错误变成 `model_not_found` 而非 token 错）
- 即：key 没问题，是 yml 指错了域名；但 yml 的 URL 还要补 `/chat/completions` 后缀
- 另：即使用对域名，`agnes-2.0-flash` 在该账号下"无可用渠道"——需查 Agnes 后台可用模型

### 🟠 P1：模型数据过期（yml `llm.models`）
- `nvidia/deepseek-ai/deepseek-v4-flash`：**2026-08-07 EOL**，上游 410 Gone
- `openrouter/owl-alpha`：上游 `No endpoints found`（模型不存在/已下架）
- `deepseek/deepseek-chat-v3-0324:free` 已转付费
- 处理：更新 `llm.models` 列表（在用的换掉、EOL 的删掉）——数据改动，不涉代码

### 🟡 P2：`/api/chat/completions` 仍走 Agent（T6 靶点）
- 详见 02-CONFUSION-POINTS 第 5 条（孤儿 session、丢历史、Tool Loop 开销）

### 🟡 P2：`LLMService.stream()` 死代码（T4 已写好，等 T6 接入口）

### 🟡 P2：Phase 4 遗留
- 429/503 判断靠异常消息字符串匹配（审查已建议改自定义异常，未改）
- RequestLog 存储 / `/api/stats` 未见实现，与 VERIFICATION "PASS" 存疑（待复核）

### 🟡 P2：`chat()` 无请求超时（2026-10-16 实测发现）
- `HttpRequest` 未设 `.timeout()`，JDK HttpClient 默认无限等
- 实测：上游挂起时 Tomcat 工作线程被占 5 分钟直至对端掐断
- 上游故障期间线程池会被持续消耗，建议与 P0 状态码修复一起处理

### 🟠 环境约定：配置双源头 + 死库（2026-10-16 定案）
- **spring-dotenv 4.0.0 是死库**：唯一注册机制 `META-INF/spring.factories` 在
  Spring Boot 4 下不再加载 → `.env` 从未被应用读过（无任何告警）
- **现状双源头**：`~/.zshrc` L175-177 export 三个 API key（其他项目在用，**用户决定保留**），
  `.env` 存 6 个变量（3 key + 3 BASE_URL）
- **约定：启动命令必须 `set -a; source .env; set +a; mvn spring-boot:run`**
  ——source 在同一 shell 内覆盖 `.zshrc` 旧值，`.env` 为项目唯一配置源
- 漏 source 的后果：BASE_URL 缺失 → **启动大声崩**（placeholder 报错，fail-loud 可接受）
- 后续可选清理：删死库 + 自写 `EnvironmentPostProcessor`（Boot 4 新 SPI）

### ℹ️ 临时事件：NVIDIA 上游降级（2026-10-16 晚）
- `integrate.api.nvidia.com` 响应 1-4 分钟；OpenRouter 报
  `DEGRADED function ... provider_name: Nvidia`（其 NVIDIA 托管模型同样挂 5 分钟）
- `step-5-preview` 不受影响（1s 返回）——模型级故障，非全面故障
- 网关侧证据：deepseek/glm 服务端最终完成（latency 220s/128s，token 数真实）

### ℹ️ 记录：SQLite 孤儿 session
- 实测 `data/agent.db` 3 行 / 2 个孤儿（curl 所致）——T6 修复后停止新增即可，存量可清

### 📌 架构事实：模型白名单
- 模型须先在 `llm.models` 登记（`ModelConfig.java:26` 抛 `未找到模型`）
- 测试白名单外的模型需先改 yml + 重启

---

### ✅ 2026-10-20 已修复留档（防重复排查）
- **流式 tool_calls 组装崩溃**：碎片 `.toString()`→`asString()`、手工拼 JSON→结构化构建、参数非法降级 try/catch → `1549977` / `c249dd2`
- **`Message` 缺蛇形字段映射 → 上游 400 → 静默空响应**：`toolCalls`/`toolCallId` 无 `@JsonProperty`，上游报 `missing field tool_call_id`；
  又因 `chatStreaming` 无状态码检查 + `data:` 行前缀过滤**双重静默**被吞成"成功空响应" → 坏历史在 SQLite 形成**自持环**（跨重启存活）→ `1549977`
  - 姊妹问题：`chatStreaming` 状态码检查已补（`1549977`），**同步 `chat()` 仍缺——上方 P0 未关**
- **`ToolEngine` 参数双形状兼容**（`isTextual` 分支，字符串/对象都收）→ `c249dd2`
- **流式 ReAct 最终回答不落盘**（同步 L154/L213 有、流式无 → 多轮失忆）→ `e13f529`
- `extractThoughtAndStream` 单通道重写 → `3189ce1`；摘要链路传 model + fail-open → `ed6ac66`；CJK 左右边界 → `618210f` / `c249dd2`

### 🆕 2026-10-20 新登记（M6 批卷已回填分级；判据与打包策略见节末）
1. 🟠 **P1** **竞态：`currentModel` 共享可变字段** — `BaseLLMAdapter:54-56` 用 `@Setter` 暴露字段，`LLMServiceFactory:54` 每个请求都 `setCurrentModel()`
   → 并发同 Provider、不同模型的两个请求互相覆盖 → **请求打到错误的模型**。
   读取端：`ConfigurableAdapter.getEffectiveModel()`（经 `super.getCurrentModel()`）→ `BaseLLMAdapter:91/188/336` 三处。
   分级：影响高（错模型+错计费）× 触发需并发（当前单人串行未实证发生）× 成本中大（改签名需全量 grep）→ **绑 T6 顺路改签名**
2. 🟠 **P1** **`reasoning_content` 丢弃** — 同步 `chat()` 只读 `choices[0].message.content`（`BaseLLMAdapter:123-124`）；
   `chatStreaming` 只读 `delta.content`（约 L390）→ 原生推理模型（step-5）的思考/偶发答案全丢：无 thinking 事件、空回答（冒烟已实证）；
   `stream()` 透传不丢（`delta` 原样嵌入 L297）——**同一模型两条出口表现不一致**。
   分级：已实证但失败可见 + 有替代模型（deepseek 正常）→ 不够 P0 → **绑 T6**
3. 🟠 **P1** **`estimateMessages` 漏 `tool_calls`** — `TokenCounter:35-39` 只数 `content+5/条`（**字段级**缺失，非字符级）。
   后果链：工具轮 assistant 的 content 近空而 tool_calls 很大 → 总量低估 → 触达 6 个下游：`ReActAgent:587`（85% 预警）、`ContextManager:41/61`（累计与截断阈值）、
   `BaseLLMAdapter:236/466`（两处请求侧估算 usage）、`AgentController:112/114`（compact 压缩比判断）→ 该截不截 → 上游超窗报错。
   分级：影响中 × 触发广（每次工具轮）× 成本极小 → **阈值族，性价比最高**
4. 🟡 **P2** **`inputTokens` 恒 0** — `ReActAgent:160`（同步 React 收尾）与 `ReActAgent:219`（PLAN 收尾）硬编码 `.inputTokens(0)`。
   分级：纯展示/统计失真，无下游拿它做决策 → 独立随手修（成本微）
5. 🟡 **P2** **状态机半接线（两种响法）** — `DONE→THINKING`（同 session 跨请求入口 `L282`/silent `L88`，白名单 DONE 只许回 IDLE）+
   `THINKING→THINKING`（工具后原地回环 `L332`/silent `L136`，白名单 THINKING 不含自指）→ 每轮必 warn。
   分级：无功能影响，告警疲劳 → 修法逐条定性（真转换补契约/归位，假转换改调用方）
6. 🟠 **P1** **`32000` 双源头写死** — `ReActAgent:37` 与 `ContextManager:26` 各存一份 `DEFAULT_CONTEXT_WINDOW`
   → 5 模型窗口不同（双向失真：大窗口过早截、小窗口截不断）+ 两处独立修改必漂移（违反阈值单一源头）→ **阈值族**
7. 🟡 **P2** **`/compact` 硬编码 secret** — `AgentController:105` 字面量 `"llm-gateway-compact-secret"`。
   分级：本地学习无实害；**上线前门禁，届时升 P0**
8. 🟡 **P2** **assistant 历史带 `<thinking>` 原文** — 思考标签进历史污染上下文 → **绑 T6**（同一批历史处理改动）
9. 🟡 **P2** **PLAN 模式 sync/stream 构造漂移** — 计划模式两条路不同构 → T6 动流路时顺手对齐
10. 🟡 **P2** **`stream()` 独立 usage 帧被跳过** — `BaseLLMAdapter:276-277` `choices` 空判 `continue` **先于** usage 读取（L280-287）
    → 供应商发"独立 usage 帧"（`choices:[]`）则真实值读不到，只按 content（L294）累加估算（推理模型的 reasoning 部分也不计）。
    分级：**先实证**（一次 curl 看上游帧结构）；若实证踩雷 → 升 P1（计费数字）→ 阈值族
11. 🟠 **P1** **T6 捆绑雷（06-01-PLAN 已记）** — `stream()` 经 `getEffectiveModel()` 的前缀处理在子类才做、直用基类处不剥；
    `ChatController` tools 恒传 null 与 `tool_choice=auto` 矛盾 → **T6 本体，做错即功能坏**
12. 🟡 **P2** **`transitionToSilent`/`transitionTo` 同构双实现** — `ReActAgent:518-528` 与 `554-564` 各带一份 compute+try/catch+warn，
    仅差"是否 emit 事件" → sync/stream 双腿漂移温床，重构可合并（参数区分 emit）
13. 🟡 **P2** **`stream()` 缺 `tool_choice` 通道** — `LLMService.stream()` 签名（T3 设计）只有 `tools`；adapter 内 L211 tools 非空即硬编码 `"auto"`，`ChatController` 流分支也无从传递 → 客户端流式请求的 `tool_choice`（`"none"`/指定函数/`"required"`）被静默覆盖为 auto。
    修法 = 扩签名（`LLMService`+`BaseLLMAdapter`+`ChatController`+`AgentController` 四处）→ 登记不扩 T6；同步分支已由 T6 归一化守卫覆盖。**T6 验证时新发现**
14. 🟡 **P2** **`ChatRequest.toolChoice` 只支持字符串形态** — OpenAI 规范里 `tool_choice` 是联合类型（`"auto"/"none"/"required"` 字符串 **或** `{"type":"function","function":{"name":..}}` 对象）；`ChatRequest:39` 声明为 `String` → 客户端发对象形态 → Jackson 反序列化失败 → **500**（T6 补测实证：`Cannot deserialize String from START_OBJECT`）。
    影响 = OpenAI 兼容承诺的缺口（标准 SDK/客户端常用对象形态强制函数）；修法 = 升 `JsonNode`/专用多态类型并贯通 `LLMService.chat` 签名。**T6 验证时新发现**
15. 🟡 **P2** **OpenRouter base-url 写成裸字面量** — `application.yml:18` `base-url: OPENROUTER_BASE_URL=https://openrouter.ai/api/v1/chat/completions`（漏 `${}`，把 .env 赋值行粘进了 yml；`.env` 里也无此变量）→ adapter 拿到整串字面量做 baseUrl → 首个 openrouter 请求在 `URI.create` 处炸（响亮失败，非静默）。
    修法一行：`base-url: https://openrouter.ai/api/v1/chat/completions`。**随手笔**。**T7 验证时新发现**
16. 🟡 **P2** **客户端断开的处理链三处失真**（7.6 断开重验实证，三次尝试一致）——
    ① `BaseLLMAdapter:244-247` 的 `cancelled` 检查点 + `"Client disconnected"` 日志**实际不可达**：典型时序是 send 先对死客户端抛异常 → 异常路退出，轮不到下一迭代的检查点（三次断开零出现）→ **06-01-PLAN §7.6 的日志期望证伪**；释放功能靠异常路 + `try-with-resources(br)` 兜住（功能✓、日志✗）。
    ② 正常断开被记为 **ERROR** `Stream request failed`（18:15:22 实证）+ `Failed to deliver error frame` DEBUG —— 客户端关页 ≠ 服务端故障，**告警误分类**（#5 告警疲劳族）。
    ③ 异常进 `GlobalExceptionHandler` → `No converter for ApiResult with preset Content-Type 'text/event-stream'` 双故障只剩 WARN（17:19:43 与 18:15:22 两次实证）——推理待证：客户端在线时同路可能挂到超时（fail-silent 亲缘）。
    修法归 SSE 打磨包：断开走 INFO/DEBUG + 检查点改为 send 失败即置位，SSE 上下文让 JSON handler 短路。**随手笔**。**T7 验证时新发现**

**分级判据（M6 定案）**：P0 = 已实证发生 **且** 无声/伪装成功 **且** 打到钱或数据——新 14 条无一满足三条全齐，故无 P0；
"高危未实证"（#1）与"已实证但可见有替代"（#2）均为 P1。

**打包策略**：**T6 包** = #11 + #2 + #1 + #8 + #9（同一波签名/历史改动，全量 grep）；
**阈值族** = #3 + #6 + #10（独立小笔可提前修）；**随手笔** = #4、#5、#7、#12；
**tool_choice 族** = #13 + #14（同波接口签名贯通，一波修）。

### 🗂 事故模式档案（2026-10-20）
- **fail-silent 双合谋**：无状态码检查 × 行前缀过滤——每层单看合理，叠起来把上游 400 吞成"成功空响应"（对照 fail-open：出错降级放行，本例是出错伪装成功）
- **持久层自持环**：坏数据 → 用坏数据的调用失败 → 失败不产生新数据 → 坏数据保留 → 永远失败；识别特征 = 首次成功、后续必败、败得一模一样
- **存储与线格式解耦**：SQLite 走固定列名 + setter，不经 Jackson 注解 → 改 `@JsonProperty` 零迁移成本
- **对照实验定位法**：向上游发 A（当前格式）/B（全规范）/C（半规范）三包画出真值表，一次看清雷的排响顺序（本例：字段名先响、arguments 形状后响且被上游宽容）

---

## 三、下一步（顺序）

1. **拆原子提交**：Adapter 重构 → T4 → 注释/chore → docs（见第一节清单）
2. **继续整体复习**（进行中）：项目全景已讲过一轮，按你的节奏深入，直到完全搞明白
3. **回 T4**：验证重写后的 `stream()`（注意：当前无 HTTP 入口，
   需 T6 接线后才能 curl 实测，或临时用 jshell/测试端点验证格式）
4. T5-T8 SSE 收尾 → 06-02 Eval
5. 顺手项：P0 状态码 bug、yml 模型列表清理、Agnes 域名对齐
