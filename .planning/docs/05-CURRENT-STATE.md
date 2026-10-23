# 当前状态（2026-10-22 晚实测）

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

### ✅ P0 已修复（`98482bd`，2026-10-21）：`BaseLLMAdapter.chat()` 不检查 HTTP 状态码
- **实证**：NVIDIA 上游返回 `410 Gone`（模型已 EOL），`chat()` 只找 body 里的
  `error` 字段，410 的 `{"type":"about:blank",...}` 没有该字段 → 落到 `choices` 缺失
  → 返回 `content:""` 且 HTTP 200"成功"
- **这就是长期"空 content"之谜的根因**（此前误判为 ReAct 不产出）
- 对照：流式路径 L227 有 `statusCode() >= 400` 检查——**同步路径漏了**
- **2026-10-21 已修**（`98482bd`）：`statusCode() >= 400` → 抛带状态码与错误体的 `RuntimeException`；
  顺带给原有 error 字段检查加 `&& !choices` 守卫（防误杀同时带 error 提示的正常回答），并修 `chatStreaming` 格式串丢错误体
- **关闭说明（证据分两层）**：正路径实测通过（minimax `595`、agnes 正常 200、7.8 Agent 回 `2`）；
  **负路径（4xx→500）未获实况复测**——当日 NVIDIA 只慢不 429，探针全部客户端超时；修复分支是确定性代码，待自然触发时观察即可
- **提交勘误**：`98482bd` 同时夹带 yml `base-url` 修复（#15），提交信息未体现——同 `2ffc420` 惯例以本条为准

### ✅ P1 已过时（2026-10-22 核验销账）：Agnes base-url 与 .env 不一致
- **现况**：yml L14 已是 `https://api.agnes-ai.cn/v1/chat/completions`（域名与 `.env` 一致、`/chat/completions` 后缀已补），模型换 `agnes/agnes-3.0-flash`；2026-10-22 经网关实测 200（`content:"ok"`），P0 关闭说明亦留有 agnes 200 记录——下述原记录全行过时，留档防重复排查
- **残留事实（不再单挂 P1）**：yml 三个 `base-url` 均硬编码字面量、不引用 `*_BASE_URL`——值已正确，引用化归"环境约定"节的配置清理后续项
- yml 硬编码 `https://apihub.agnes-ai.com/v1/chat/completions` → 用该 key 报 `Invalid token`（原记录）
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
  - 姊妹问题：`chatStreaming` 状态码检查已补（`1549977`），同步 `chat()` 已补（`98482bd`）——**上方 P0 已关**
- **`ToolEngine` 参数双形状兼容**（`isTextual` 分支，字符串/对象都收）→ `c249dd2`
- **流式 ReAct 最终回答不落盘**（同步 L154/L213 有、流式无 → 多轮失忆）→ `e13f529`
- `extractThoughtAndStream` 单通道重写 → `3189ce1`；摘要链路传 model + fail-open → `ed6ac66`；CJK 左右边界 → `618210f` / `c249dd2`

### 🆕 2026-10-20 新登记（M6 批卷已回填分级；判据与打包策略见节末）
1. 🟠 **P1** **竞态：`currentModel` 共享可变字段** — `BaseLLMAdapter:54-56` 用 `@Setter` 暴露字段，`LLMServiceFactory:54` 每个请求都 `setCurrentModel()`
   → 并发同 Provider、不同模型的两个请求互相覆盖 → **请求打到错误的模型**。
   读取端：`ConfigurableAdapter.getEffectiveModel()`（经 `super.getCurrentModel()`）→ `BaseLLMAdapter:91/188/336` 三处。
   分级：影响高（错模型+错计费）× 触发需并发（当前单人串行未实证发生）× 成本中大（改签名需全量 grep）→ **绑 T6 顺路改签名**
   → ✅ 已修（`e7df86c`：模型名改方法参数——`LLMService` 5 方法全加 `String model` 首参、删 `currentModel` 字段及 getter/setter、Factory 删 `setCurrentModel` 块、9 调用点补参，8 文件编译器清扫保证同笔原子；补笔 `5a94a12`——手工改名局部变量 `model`→`respModel` 漏了下游两处引用，`data.model` 退化成裸请求名、`respModel` 变死变量；再补 `c170ccd` 完成日志参数）
   → **行为证据**（2026-10-22 实测）：奇偶复验 `data.model` 回到上游回显（`minimax/...` 去前缀）；NVIDIA 同 Provider 双模型三轮并发 **6/6 归属正确零串号**（竞态定向判别测）；日志零 ERROR/WARN
   → **补笔教训**：javac 对"同类型、同作用域"的新引用静默放行——**编译器保类型，保不了本意**；是修复前后同请求的 `data.model` 奇偶对比揪出来的 → 手工改名必须逐引用过一遍，优先用 IDE 重命名符号
2. 🟠 **P1** **`reasoning_content` 丢弃** — 同步 `chat()` 只读 `choices[0].message.content`（`BaseLLMAdapter:123-124`）；
   `chatStreaming` 只读 `delta.content`（约 L390）→ 原生推理模型（step-5）的思考/偶发答案全丢：无 thinking 事件、空回答（冒烟已实证）；
   `stream()` 透传不丢（`delta` 原样嵌入 L297）——**同一模型两条出口表现不一致**。
   分级：已实证但失败可见 + 有替代模型（deepseek 正常）→ 不够 P0 → **绑 T6**
   → ✅ 已修（`e59ffdc`：`ChatResponse` 加 `reasoningContent` + `LLMService`/`chatStreaming` 加 `onReasoningDelta` 第五参 + `BaseLLMAdapter` 同步读 `reasoning_content`/`reasoning`（DeepSeek 原生名优先、OpenRouter 归一名兜底）+ 流式累计入最终响应 + 两 Agent 接 `StreamEvent.thinking`；补笔 `fdfdde8`——e59ffdc 誊抄走样把 content 行写成 `root.path("choices").asString()`（对**数组**调无参 asString → 每次同步必 500，#20 同族雷），补笔同笔含 5 内置工具参数读取补 `asString("")` 显式默认值加固）
   → **行为证据**（2026-10-22 实测）：流式正例 minimax `event:thinking` **102 帧**（首帧即模型内心独白，修复前恒 0）；同步负例 agnes **200** + `reasoningContent:''` + content/model 回显完好（修复前同请求 500）；ReAct `mode:REACT` 状态序列回归 `THINKING→ACTING→THINKING→DONE`；日志窗口零 ERROR/WARN
   → **待补验**：同步正例（真吐 reasoning 的模型打同步路、`data.reasoningContent` 非空）——环境三连击（minimax `:free` 2026-10-22 退役 404；NVIDIA 推理端点排队 >280s；OpenRouter 免费模型日限 50 次耗尽 429）。**低风险**：上游字段名/类型已被当日直连探针实证（`message.reasoning` 字符串），读取模式与已实证的流式路同构
   → **注记**：`reasoningContent` 实为空串 `''` 非 null（`asString("")` 默认值语义），`ChatResponse` 注释"无推理模型为null"与行为有出入——注释级、功能等价，不返工
   → **誊抄教训**：参考答案手抄两处走样（数组当对象读 → 全同步 500；`get(0)` 越界返 null 隐患）双双过编译——"编译器保类型，保不了本意"再实证；行为层判别测当场揪出 → 誊抄后与参考逐字符对照，或直接复制粘贴
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
   → ✅ 已修（`f6a262f`：新建 `ThoughtTags` 单一定义处（`OPEN`/`CLOSE` 常量 + `strip`），ReActAgent 两常量改委托（抽取器零改动）、同步循环局部变量收编出口与轮内追加、流式 `fullResponse` 单点收编三处消费、两处 planJson 同剥，SimpleAgent 四处存储点同剥；补笔 `6ce84b7`——`strip` 内 `strayOpen > 0` 誊抄走样，"标签在开头"（`indexOf` 返回 0）被误判为不存在而漏剥，jshell 实跑现形 → 改 `>= 0`）
   → **行为证据**（2026-10-23 实测）：修复前基线（同提示词强制标签）同步双路响应与 DB 三行全带原始标签（row 595/597/599）；修复后 **铁证对**——同一次 ReAct 流式请求中 `event:thinking` 帧证明原始有标签块、入库行 607 无标签；jshell 对真实编译类跑五规则矩阵（无标签原样/成对整块删/游离闭合删/开头未闭合删至尾/中间未闭合裁断）全绿；修复后新会话 6 条 assistant 行含标签 0；日志窗口零 ERROR/WARN
   → **边界注记**：① 同步双路当轮模型改用 markdown 星号写思考（模型=随机变量），同步存储无直接运行时见证——证据 = diff 调用点逐行审计 + 纯函数 jshell 实证 + 同函数系统级已证（T-c）；② SimpleAgent 流式显示推原始帧（无抽取器，本轮未触发）＝ #8 范围外已知边界；③ 历史污染行 172 不回填（不做数据迁移）；④ row 615 空 assistant 行 = 模型工具轮 content 本空，修复前后同行为非回归
   → **方法论**：`indexOf` 返回三态——`-1` 无、`0` 在开头、`>0` 在中间，**存在性判断必须 `>= 0`**——边界值是判断表达式的事故高发区（`get(0)`/`path(0)`、无参 `asString()` 同族）；补笔由 diff 审计 + jshell 规则矩阵当场逮住 → 纯函数改动配规则矩阵比跑模型可靠（模型是随机变量）
9. 🟡 **P2** **PLAN 模式 sync/stream 构造漂移** — 计划模式两条路不同构 → T6 动流路时顺手对齐
10. 🟡 **P2** **`stream()` 独立 usage 帧被跳过** — `BaseLLMAdapter:276-277` `choices` 空判 `continue` **先于** usage 读取（L280-287）
    → 供应商发"独立 usage 帧"（`choices:[]`）则真实值读不到，只按 content（L294）累加估算（推理模型的 reasoning 部分也不计）。
    分级：**先实证**（一次 curl 看上游帧结构）；若实证踩雷 → 升 P1（计费数字）→ 阈值族
11. 🟠 **P1** **T6 捆绑雷（06-01-PLAN 已记）** — `stream()` 经 `getEffectiveModel()` 的前缀处理在子类才做、直用基类处不剥；
    `ChatController` tools 恒传 null 与 `tool_choice=auto` 矛盾 → **T6 本体，做错即功能坏**
    → ✅ 已处理（`b60cdee`：同步分支归一化守卫 + 前缀剥离经流式 E2E 实证；流式 tool_choice 残口另立 #13）
12. 🟡 **P2** **`transitionToSilent`/`transitionTo` 同构双实现** — `ReActAgent:518-528` 与 `554-564` 各带一份 compute+try/catch+warn，
    仅差"是否 emit 事件" → sync/stream 双腿漂移温床，重构可合并（参数区分 emit）
13. 🟡 **P2** **`stream()` 缺 `tool_choice` 通道** — `LLMService.stream()` 签名（T3 设计）只有 `tools`；adapter 内 L211 tools 非空即硬编码 `"auto"`，`ChatController` 流分支也无从传递 → 客户端流式请求的 `tool_choice`（`"none"`/指定函数/`"required"`）被静默覆盖为 auto。
    修法 = 扩签名（`LLMService`+`BaseLLMAdapter`+`ChatController`+`AgentController` 四处）→ 登记不扩 T6；同步分支已由 T6 归一化守卫覆盖。**T6 验证时新发现**
    → ✅ 已修（`571f2a4`：`LLMService.stream` 加 `JsonNode` 三参 default 重载 + adapter `effectiveToolChoice` 透传 + 三处默认值改 `om.valueToTree("auto")` + `ChatController` 流分支接线；补笔 `60abdcd` 删旧 if 死代码——首笔落地后旧分支仍拦在前面使新调用不可达，教训："grep 命中 ≠ 控制流可达"，验收先走查控制流）
    → **勘误 4**：上句修法"四处含 `AgentController`"系误报——`AgentController` 调的是 `agent.stream()`（`Agent` 接口），与 `LLMService.stream()` 无关，实为 `LLMService`/`BaseLLMAdapter`/`ChatController` 三文件
    → **判别测**（2026-10-22 实测）：流式 + `tool_choice:"none"` + 强制工具措辞 → 92 帧零 `tool_calls`；同型对照仅 `tool_choice` 换对象形态 → `tool_calls` 在场——单变量差异归因透传真实生效
14. 🟡 **P2** **`ChatRequest.toolChoice` 只支持字符串形态** — OpenAI 规范里 `tool_choice` 是联合类型（`"auto"/"none"/"required"` 字符串 **或** `{"type":"function","function":{"name":..}}` 对象）；`ChatRequest:39` 声明为 `String` → 客户端发对象形态 → Jackson 反序列化失败 → **500**（T6 补测实证：`Cannot deserialize String from START_OBJECT`）。
    影响 = OpenAI 兼容承诺的缺口（标准 SDK/客户端常用对象形态强制函数）；修法 = 升 `JsonNode`/专用多态类型并贯通 `LLMService.chat` 签名。**T6 验证时新发现**
    → ✅ 已修（`571f2a4`：`ChatRequest.toolChoice` String→`JsonNode`——string|object 联合类型，网关忠实搬运、校验归上游；`LLMService.chat` 签名贯通）。实测同步对象形态 200 + `finishReason:tool_calls` + calculator 参数正确（修复前 500）
15. 🟡 **P2** **OpenRouter base-url 写成裸字面量** — `application.yml:18` `base-url: OPENROUTER_BASE_URL=https://openrouter.ai/api/v1/chat/completions`（漏 `${}`，把 .env 赋值行粘进了 yml；`.env` 里也无此变量）→ adapter 拿到整串字面量做 baseUrl → 首个 openrouter 请求在 `URI.create` 处炸（响亮失败，非静默）。
    修法一行：`base-url: https://openrouter.ai/api/v1/chat/completions`。**随手笔**。**T7 验证时新发现**
    → ✅ 已修（`98482bd` 夹带；同日白名单 `nemotron` → `minimax-m3.1-flash:free` 换入 `075f392`）
16. 🟡 **P2** **客户端断开的处理链三处失真**（7.6 断开重验实证，三次尝试一致）——
    ① `BaseLLMAdapter:244-247` 的 `cancelled` 检查点 + `"Client disconnected"` 日志**实际不可达**：典型时序是 send 先对死客户端抛异常 → 异常路退出，轮不到下一迭代的检查点（三次断开零出现）→ **06-01-PLAN §7.6 的日志期望证伪**；释放功能靠异常路 + `try-with-resources(br)` 兜住（功能✓、日志✗）。
    ② 正常断开被记为 **ERROR** `Stream request failed`（18:15:22 实证）+ `Failed to deliver error frame` DEBUG —— 客户端关页 ≠ 服务端故障，**告警误分类**（#5 告警疲劳族）。
    ③ 异常进 `GlobalExceptionHandler` → `No converter for ApiResult with preset Content-Type 'text/event-stream'` 双故障只剩 WARN（17:19:43 与 18:15:22 两次实证）——推理待证：客户端在线时同路可能挂到超时（fail-silent 亲缘）。
    修法归 SSE 打磨包：断开走 INFO/DEBUG + 检查点改为 send 失败即置位，SSE 上下文让 JSON handler 短路。**随手笔**。**T7 验证时新发现**
17. 🟡 **P2** **同步 React 路异常后 session 状态残留 → 假 WARN** — `executeReactModeSync` 入口 `ReActAgent:91` 置 THINKING 后，循环内异常（如 `L106` `getLlmService(null)` 抛 `IllegalArgumentException`）无 catch/finally 兜底、直穿全局处理器回 400，session 状态停在 THINKING；下个同 session 请求入口再置 THINKING → 空打 `非法状态转换: THINKING -> THINKING`（`transitionTo` 在 warn 后 `return next` 强行置目标态，功能无损）
    实证（2026-10-22 冒烟自伤）：14:36:34 失败请求（缺 model）留脏态 → 14:36:57 成功请求报 WARN；历史同款 26 次（13:22-13:45）证既有现象非本次回归。排障盲区：同步路成功转换静默（`transitionTo` 的 debug 日志挂在 `emitter != null` 下），脏态零痕迹只能靠 WARN 反推
    分级：仅告警噪音（#5 告警疲劳族亲缘）→ 修法：React 同步路异常兜底 transition ERROR（或入口放行 THINKING 自指）→ **随手笔**
18. 🟡 **P2** **compact `compressionRatio` 运算符优先级错位** — `AgentController:129` `1.0 - (double) compressedTokens / originalTokens * 100`：`*` 与 `/` 同优先级左结合，乘 100 先跟了除法 → 算成 `1−37.3` 显示 **`-36.3%`**（真实压缩 62.7%）；纯显示层错数，压缩功能本身正常。修 = 括号整体乘 100。**2026-10-22 #1 收尾验证时新发现**
    → ✅ 已修（`445f54d`）。复验 compact 返回 `54.0%`（2291→1055 token）正数 ✓
19. 🟡 **P2** **SimpleAgent 经 HTTP 不可达** — `AgentRequest:31-33` 自定义 getter `mode != null ? mode : REACT` 把显式 `"mode":null` 兜底回 REACT → 控制器两分支（chat L67 / stream L88）判 `getMode() != null` **恒真** → else 分支成**死代码**，而流式分支 javadoc 明写 "mode=react|plan_execute → ReAct，其他/null → SimpleAgent（向后兼容）"——设计与实现背离；前序对话"显式 `mode:null` 即达 SimpleAgent"的结论系漏读该 getter 的误判，以本条为准。
    消费方核验：ReActAgent L80/L291 为 `== PLAN_EXECUTE` 比较（null 安全）、L549 克隆拷贝下游同构 → 删 getter 无连锁。**2026-10-22 #1 收尾验证时新发现**
    → ✅ 已修（`7d8fe8f`：删三行自定义 getter）。复验：同步 `mode:null` → 200 + 工具轮（SimpleAgent L73 行为覆盖）；流式 `mode:null` → 窗口零状态行 + 零 ReActAgent 行 + `Streaming chat request` 在场（L148 行为覆盖，修复前恒出 4 条 ReAct 状态行）
20. 🟡 **P2** **SimpleAgent 流式事件序列化双雷** — ① `SimpleAgent:159` 对 `tool_calls` 的 **ArrayNode 调 `.asString()`** → Jackson 拒绝标量强制转换，首工具轮必炸（堆栈实证 `'ArrayNode' asString() cannot coerce`）；② L173 手搓 `formatted` JSON，工具输出带引号即发畸形帧（file_io/web_fetch 必中）。
    **定性：#19 修复暴露的潜伏雷，非回归**——路由打通前该行不可达，"不可达的代码不是安全的代码，是没被测过的代码"。**2026-10-22 #19 复验时新发现**
    → ✅ 已修（`211e315`：`toString()` 对齐 ReActAgent L339 + `Map.of`/`writeValueAsString` 对齐 L350-351）。复验：35 帧、零 error 事件、尾部 `event:done`、两工具调用全执行；编译告警仅 ToolEngine 既存 deprecation 一条

### ✅ 2026-10-22 批次销账：阈值族 + 随手笔全清（7 条）
- **#3** estimateMessages 漏 tool_calls → ✅ `71547e2`（`estimateMessage` 统一口径；ContextManager:53 第 7 现场同批修复）
- **#6** 32000 双源头 → ✅ `c0cdf81`（yml `llm.default-context-window` 单一源头 + 两处 @Value 注入；per-model 真实窗口另立后续项）
- **#10** usage 帧被跳过 → ✅ `a9660b6`（读取前移）+ **实证定性**：对照 A（不带 include_usage）上游不发独立 usage 帧 → 潜伏未踩雷维持 P2；对照 B 证 OpenRouter 将 usage 附于正常帧
- **#4** inputTokens 恒 0 → ✅ `b18591a`（同步 React/PLAN 双路跨轮累计；实测 `inputTokens:2109/2277`）
- **#5** 状态机半接线 → ✅ `16b244e`（终态重入白名单 DONE/ERROR→{IDLE,THINKING,PLANNING} + 流式补 ACTING 跳；实测同 session 重入零告警，流式 STATE 序列 `THINKING→ACTING→THINKING→DONE` 首次如实广播）
- **#7** compact 硬编码 secret → ✅ `6d6ed65`（@Value 注入 + `.env` `COMPACT_SECRET`；实测错值 401/真值 200；"上线前门禁"标签解除）
- **#12** 迁移双实现 → ✅ `606109e`（合并单一 `transitionTo`，emitter 可空即静默）
- **提交勘误 3**：`a9660b6`（fix[sse]）夹带 ReActAgent 的 PLAN 空计划守卫删除——实证无害（`getJsonNode` 内有重复守卫 + `@NonNull`，行为等价；null 时日志略糙），以本条为准；`2408a1d` 标题声明双主题同惯例。**"顺手夹带"已三振（`98482bd`/`13b12a8`/`a9660b6`），此后每笔提交前 diff 逐行对标题自查。**

### ✅ 2026-10-22 批次销账：tool_choice 族全清（2 条）
- **#13** `stream()` 缺 `tool_choice` 通道 → ✅ `571f2a4` + 补笔 `60abdcd`（死代码删除；详见 #13 条目）
- **#14** `toolChoice` 只支持字符串 → ✅ `571f2a4`（详见 #14 条目）
- **判别测五连全绿**（2026-10-22 实测）：① 流式 `"none"` + 强制工具措辞 → 92 帧零 `tool_calls`；② 同步对象形态 → 200 + `finishReason:tool_calls` + calculator 参数正确（修复前 500）；③ 流式对象形态 → `tool_calls` 5 处；④ 同步无 tools → 200 正常答；⑤ agent 冒烟 → `777*333` 工具轮闭环。①③构成单变量对照（仅 `tool_choice` 之差），证透传到上游真实生效
- **勘误 4**：#13 原记修法"四处含 `AgentController`"系误报，实为三文件——详见 #13 条目

### ✅ 2026-10-22 晚批次销账：竞态族 #1 + 验证捞出三连（4 条）
- **#1** `currentModel` 共享字段竞态 → ✅ `e7df86c` + 补笔 `5a94a12`/`c170ccd`（证据链与补笔教训详见 #1 条目）
  - 核心判别测：NVIDIA 同 Provider 双模型（`step-5-preview`/`glm-5.3-flash`）三轮并发交替，每响应 `data.model` 逐一对号
- **#18 / #19 / #20** 验证捞出、当日修完 → ✅ `445f54d` / `7d8fe8f` / `211e315`（详见三条目）
  - **路径教训**：删 getter 打通死路由（#19）→ 立刻引爆同路径潜伏雷（#20）——**不可达的代码不是安全的代码，是没被测过的代码**；修复路由是给暗道点灯，不是制造回归
- **SimpleAgent 双路行为覆盖补全**：此前仅编译级（编译器清扫），本轮同步（L73）/流式（L148）均获行为级证据
- 收尾日志：新实例 49 行窗口零 ERROR / 零 WARN / 零 Stream 处理失败

### ✅ 2026-10-22 深夜批次销账：#2 reasoning 通道（1 条）
- **#2** `reasoning_content` 丢弃 → ✅ `e59ffdc` + 补笔 `fdfdde8`（证据链与誊抄教训详见 #2 条目；**同步正例待环境恢复补验**，不阻塞 #8/#9）
- **判别指纹沉淀**：流式判别 = `event:thinking` 帧数（修复前恒 0，实测 102）；同步判别 = `data.reasoningContent` 在场 + content 非空；负对照 = 无 reasoning 字段的模型（agnes）零副作用
- **环境事件三连**（2026-10-22 晚实录）：① OpenRouter `minimax/minimax-m3.1-flash:free` **退役**（404 实证"free launch period ended"）→ 白名单首条已死、快速测试模型需更换；② NVIDIA `integrate.api.nvidia.com` 主机通（1.3s 200）但推理端点排队 **>280s**（curl 退出码 28，历史 1-10s，判临时拥塞）；③ OpenRouter 免费模型**日限 50 次耗尽**（429 `free-models-per-day`，充值 10 刀解锁 1000 次/日）
- **勘误 5**：`e59ffdc` 与 `fdfdde8` 标题均整句复制（含括号旁白与自查指令）；`fdfdde8` 实为"同步 content 修复 + 5 工具加固"两件事一笔且复用 reasoning 标题——已推送不重写，以 `fix(api): 修正同步 content 读取路径` + `fix(tool): 工具参数补 asString 默认值` 两条语义为准。**教训**：只取建议标题行、按本笔实际内容改写；两件事拆两笔

### ✅ 2026-10-23 批次销账：#8 思考标签剥离（1 条）
- **#8** assistant 历史带 `<thinking>` 原文 → ✅ `f6a262f` + 补笔 `6ce84b7`（证据链、边界注记与 indexOf 方法论详见 #8 条目）
- **修复形态**：`ThoughtTags` 单一定义处双出口（常量 + `strip`），抽取器与存储边界共用一份标签字符串；**剥离边界放 Agent 层不碰适配器**——`/api/chat/completions` 是 OpenAI 兼容代理出口必须忠实上游，历史/返回值属 Agent 层自有语义
- **判别姿势沉淀**：纯函数用 jshell 打真实编译类做**五规则矩阵**（一次调用同时验修复与回归）；系统级铁证对 = "原始有标签"侧信道（`event:thinking` 帧）与"库里无标签"（DB 行）同请求互证——模型不吐标签时同步路结构性无侧信道，如实声明为静态链证据
- **T6 包清零至 #9**；row 615 空工具轮行为、SimpleAgent 流式显示原始帧两条边界入 #8 注记

**分级判据（M6 定案）**：P0 = 已实证发生 **且** 无声/伪装成功 **且** 打到钱或数据——新 14 条无一满足三条全齐，故无 P0；
"高危未实证"（#1）与"已实证但可见有替代"（#2）均为 P1。

**打包策略**：**T6 包** = #11 + #2 + #1 + #8 + #9（同一波签名/历史改动，全量 grep）→ 已清 #11（`b60cdee`）、#1（竞态批）、#2（深夜批）与 #8（10-23 批），**残余 #9**；
**阈值族** = #3 + #6 + #10（已清）；**随手笔** = #4、#5、#7、#12（已清），另挂 #16 + #17；
**tool_choice 族** = #13 + #14（已清）。

### 🗂 事故模式档案（2026-10-20）
- **fail-silent 双合谋**：无状态码检查 × 行前缀过滤——每层单看合理，叠起来把上游 400 吞成"成功空响应"（对照 fail-open：出错降级放行，本例是出错伪装成功）
- **持久层自持环**：坏数据 → 用坏数据的调用失败 → 失败不产生新数据 → 坏数据保留 → 永远失败；识别特征 = 首次成功、后续必败、败得一模一样
- **存储与线格式解耦**：SQLite 走固定列名 + setter，不经 Jackson 注解 → 改 `@JsonProperty` 零迁移成本
- **对照实验定位法**：向上游发 A（当前格式）/B（全规范）/C（半规范）三包画出真值表，一次看清雷的排响顺序（本例：字段名先响、arguments 形状后响且被上游宽容）

---

## 三、下一步（顺序，2026-10-23 更新）

1. **T6 包残余**：#9 PLAN 漂移（ReActAgent 主战场，同波签名/历史改动，全量 grep）；#2 同步正例补验挂环境恢复后（换白名单模型或 NVIDIA 排队恢复，见深夜批环境事件）
2. **随手笔挂账**：#16 SSE 断开打磨包 + #17 同步 React 假 WARN
3. **06-02 Eval 实施**（06-02-PLAN T1-T12 为工作手册；06-01-PLAN 遗留 T5-T8 届时核对）
4. M7 复习考（定案：Eval 完工后当结业考）
5. **顺手项**：yml 模型列表清理（P1 模型数据过期；P0 状态码 `98482bd` 已修、Agnes 域名已核验销账 2026-10-22）——**`minimax-m3.1-flash:free` 已退役必换**（2026-10-22 环境事件，白名单首条现为死模型）；快速测试模型候选：OpenRouter nemotron 系免费推理模型（`nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free` 直连探针因日限 429 未及验证）或 NVIDIA 三杰（待排队恢复）

> 旧清单"拆原子提交 / 整体复习 / 回 T4"三项已失效（2026-10-22 核验：工作区净；`stream()` 经多轮 SSE 判别测实证）。
