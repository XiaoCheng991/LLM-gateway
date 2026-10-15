# 项目总结：Java 21 + Spring Boot 实现的 LLM 接口适配器

## 项目背景
1. 为什么要做这个项目？（一句话说清楚）
2. 解决了什么问题？

## 技术选型
1. 为什么用 Java 21 + Spring Boot？
2. 为什么不用 LangChain4j？（手写的目的）
3. HTTP 客户端为什么选 java.net.http.HttpClient？

## 架构设计（核心）
1. 适配器模式 — 画个简图：接口 → 多个实现
2. 工厂模式 — 按前缀路由的逻辑
3. 分层结构 — Controller → Service(Factory) → Adapter → 外部 API

## 核心代码片段
1. LLMService 接口定义
2. 工厂的 switch 路由
3. Adapter 里发 HTTP 请求的骨架

## 踩坑记录（最有价值的）
1. JSON 字段大小写不一致（Content vs content）
2. ObjectMapper 导包问题（Spring Boot 4.x 的包名变化）
3. Nvidia 超时问题
4. @Autowired vs @Resource 的区别

## 如何扩展
1. 加一个新的 Provider 需要几步？
2. 目录结构说明