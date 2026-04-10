# 模块架构与技术栈

## Maven 多模块分层

```
ragent/
├── framework/          # 基础设施层 - 与业务无关的通用能力
├── infra-ai/           # AI 基础设施层 - 屏蔽不同模型供应商差异
├── bootstrap/          # 业务层 - RAG 核心链路 + 管理后台 + 入库流水线
├── mcp-server/         # MCP 工具服务 - 独立的 MCP 协议实现
└── frontend/           # 前端 - React 18 管理界面 + 聊天页面
```

### 依赖关系

```
bootstrap → infra-ai → framework
mcp-server（独立部署，通过 HTTP 与 bootstrap 通信）
```

## 各模块职责

### framework（38 个类）

与业务完全无关的横切关注点：

| 能力 | 关键类 |
|------|--------|
| 三级异常体系 | `AbstractException` → `ClientException` / `ServiceException` / `RemoteException` |
| 统一响应体 | `Result<T>` + `Results` 工厂 |
| 统一异常拦截 | `GlobalExceptionHandler` |
| 分布式 ID | `SnowflakeIdInitializer` + `CustomIdentifierGenerator` |
| 双维度幂等 | `@IdempotentSubmit`（提交幂等） + `@IdempotentConsume`（消费幂等） |
| 用户上下文 | `UserContext` — 基于 TTL 跨线程透传 |
| Trace 上下文 | `RagTraceContext` — traceId + nodeId 栈 |
| SSE 封装 | `SseEmitterSender` — 线程安全的 SSE 写入 |
| MQ 适配 | `RocketMQProducerAdapter` + 事务消息支持 |
| 通用约定 | `ChatRequest` / `ChatMessage` / `RetrievedChunk` |

### infra-ai（44 个类）

屏蔽不同 AI 模型供应商的差异，提供统一的 Chat / Embedding / Rerank 能力：

| 能力 | 关键类 |
|------|--------|
| Chat 统一接口 | `LLMService` → `RoutingLLMService`（@Primary） |
| Chat 客户端 | `BaiLianChatClient` / `OllamaChatClient` / `VllmChatClient` / `SiliconFlowChatClient` |
| Chat 公共基类 | `AbstractOpenAIStyleChatClient` — 复用 OpenAI 兼容协议 |
| Embedding | `EmbeddingService` → `RoutingEmbeddingService` |
| Rerank | `RerankService` → `RoutingRerankService` |
| 模型路由 | `ModelSelector`（候选排序） + `ModelRoutingExecutor`（降级执行） |
| 健康检查 | `ModelHealthStore` — 三态熔断器 CLOSED/OPEN/HALF_OPEN |
| 流式输出 | `StreamCallback` / `ProbeBufferingCallback`（首包探测装饰器） |
| SSE 解析 | `OpenAIStyleSseParser` — 解析 `data: {...}` 行 |
| HTTP 工具 | `OkHttpClient` + `HttpResponseHelper` + `ModelUrlResolver` |
| 配置 | `AIModelProperties` — 多 Provider 多模型 YAML 配置 |

### bootstrap（核心业务，180+ 个类）

按业务域组织为 5 个包：

```
bootstrap/src/main/java/com/nageoffer/ai/ragent/
├── rag/                # RAG 核心 - 问答全链路
│   ├── controller/     # API 入口（Chat、Conversation、Trace、Settings 等）
│   ├── service/        # 业务编排（RAGChatService、TraceService 等）
│   ├── core/           # 核心引擎
│   │   ├── rewrite/    # 问题改写与拆分
│   │   ├── intent/     # 意图识别（树形分类器）
│   │   ├── retrieve/   # 检索引擎（多通道 + 后处理链）
│   │   ├── memory/     # 会话记忆（滑动窗口 + 摘要压缩）
│   │   ├── guidance/   # 歧义引导
│   │   ├── prompt/     # Prompt 模板加载与组装
│   │   └── mcp/        # MCP 工具注册与调用
│   ├── aop/            # 切面（Trace、限流）
│   ├── config/         # 配置（线程池、RAG 参数、搜索通道）
│   ├── dao/            # 数据访问
│   └── dto/            # 数据传输对象
├── ingestion/          # 文档入库 ETL
│   ├── engine/         # 流水线执行引擎
│   ├── node/           # 节点实现（Fetcher/Parser/Chunker/Indexer 等）
│   ├── domain/         # 领域模型（Context/Settings/Result/Enums）
│   └── service/        # 任务管理
├── knowledge/          # 知识库管理
│   ├── controller/     # CRUD API
│   ├── service/        # 知识库/文档/分块管理
│   └── schedule/       # 定时刷新（URL 类型文档自动更新）
├── admin/              # 管理后台仪表板
└── user/               # 用户认证（Sa-Token）
```

### mcp-server（16 个类）

独立部署的 MCP 工具服务：

| 能力 | 关键类 |
|------|--------|
| JSON-RPC 协议 | `JsonRpcRequest` / `JsonRpcResponse` |
| 工具注册表 | `DefaultMCPToolRegistry` — 自动发现 `MCPToolExecutor` Bean |
| 请求分发 | `MCPDispatcher` → `MCPEndpoint` |
| 示例工具 | `WeatherMCPExecutor` / `SalesMCPExecutor` / `TicketMCPExecutor` |

## 技术栈一览

| 层面 | 技术 |
|------|------|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.5.7 |
| ORM | MyBatis Plus 3.5.14 |
| 前端 | React 18 + Vite + TypeScript |
| 向量数据库 | Milvus 2.6 |
| 关系数据库 | PostgreSQL（或 MySQL） |
| 缓存/限流 | Redis + Redisson 4.0 |
| 对象存储 | S3 兼容（RustFS） |
| 消息队列 | RocketMQ 5.x |
| 文档解析 | Apache Tika 3.2 |
| 认证 | Sa-Token 1.43 |
| HTTP | OkHttp 4.12 |
| 线程上下文 | Transmittable Thread Local (TTL) 2.14 |
| 代码规范 | Spotless（Apache License 自动格式化） |
