# Ragent 项目学习指南

> 本目录是为快速了解 Ragent 项目而编写的系统性学习文档。

## 文档索引

| 文档 | 内容 |
|------|------|
| [architecture.md](./architecture.md) | 模块分层、技术栈、目录结构 |
| [rag-pipeline.md](./rag-pipeline.md) | RAG 核心问答链路（从用户提问到流式回答） |
| [ingestion-pipeline.md](./ingestion-pipeline.md) | 文档入库 ETL 流水线 |
| [model-routing.md](./model-routing.md) | 模型路由、健康检查与容错降级 |
| [database-design.md](./database-design.md) | 数据库表设计总览（20 张表） |
| [technical-highlights.md](./technical-highlights.md) | 技术难点与亮点分析 |
| [design-patterns.md](./design-patterns.md) | 项目中使用的设计模式及对应代码 |

## 项目一句话概括

Ragent 是一个基于 **Java 17 + Spring Boot 3 + React 18** 的企业级 Agentic RAG 系统，覆盖从文档入库、多路检索、意图识别、问题重写、模型路由到流式问答的全链路能力。

## 快速定位核心入口

| 你想了解的 | 入口类 |
|-----------|--------|
| 用户聊天请求入口 | `RAGChatController` → `RAGChatServiceImpl.streamChat()` |
| RAG 主流程编排 | `RAGChatServiceImpl` |
| 问题改写与拆分 | `MultiQuestionRewriteService` |
| 意图识别 | `IntentResolver` → `IntentClassifier` |
| 多通道检索 | `MultiChannelRetrievalEngine` |
| Rerank 重排序 | `RerankPostProcessor` → `RoutingRerankService` |
| 模型路由与降级 | `ModelRoutingExecutor` → `ModelHealthStore` |
| 文档入库引擎 | `IngestionEngine` |
| MCP 工具调用 | `RetrievalEngine.executeMcpTools()` |
| 会话记忆管理 | `DefaultConversationMemoryService` |
| 全链路追踪 | `RagTraceAspect` + `@RagTraceNode` / `@RagTraceRoot` |
