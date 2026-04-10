# RAG 核心问答链路

## 整体流程

用户发起一次提问，在 Ragent 内部经过以下 7 个阶段：

```
用户提问
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│ 1. 会话记忆加载    memoryService.loadAndAppend()             │
│    加载历史对话 + 追加当前问题到记忆                           │
├──────────────────────────────────────────────────────────────┤
│ 2. 问题改写与拆分  queryRewriteService.rewriteWithSplit()    │
│    术语归一化 → LLM 改写 → 子问题拆分                        │
├──────────────────────────────────────────────────────────────┤
│ 3. 意图识别        intentResolver.resolve()                  │
│    每个子问题并行分类 → 命中 KB / MCP / SYSTEM 意图           │
├──────────────────────────────────────────────────────────────┤
│ 4. 歧义引导        guidanceService.detectAmbiguity()         │
│    置信度不足时返回引导性追问，让用户澄清                      │
├──────────────────────────────────────────────────────────────┤
│ 5. 检索 + 重排序   retrievalEngine.retrieve()                │
│    多通道并行检索 → 去重 → Rerank → Context 格式化            │
├──────────────────────────────────────────────────────────────┤
│ 6. Prompt 组装     promptBuilder.buildStructuredMessages()   │
│    System Prompt + 历史记忆 + 检索上下文 + 用户问题           │
├──────────────────────────────────────────────────────────────┤
│ 7. 流式生成        llmService.streamChat()                   │
│    SSE 实时推送 → 模型回答 → 回调落库                         │
└──────────────────────────────────────────────────────────────┘
  │
  ▼
前端 SSE 接收渲染
```

## 入口类

**Controller**: `RAGChatController.chat()` (`rag/controller/RAGChatController.java:54`)
- GET `/rag/v3/chat?question=xxx&conversationId=xxx&deepThinking=false`
- 返回 `SseEmitter`，produces `text/event-stream`
- 加了 `@IdempotentSubmit` 防止同一用户并发提问

**Service**: `RAGChatServiceImpl.streamChat()` (`rag/service/impl/RAGChatServiceImpl.java:91`)
- 加了 `@ChatRateLimit` 限流切面（排队式并发限流）
- 这是整条 RAG 链路的编排入口

## 阶段详解

### 1. 会话记忆加载

```
DefaultConversationMemoryService.loadAndAppend()
  ├── 从 DB 加载历史消息（滑动窗口，近 N 轮）
  ├── 如果超限，触发异步摘要压缩
  ├── 追加当前 user 消息
  └── 返回完整 history: List<ChatMessage>
```

**关键设计**：
- 滑动窗口控制 Token 成本，不会把 20 轮对话全塞给模型
- 超限时用 LLM 自动生成摘要，以 System 角色消息注入历史
- 摘要持久化到 `t_conversation_summary` 表，避免重复生成

### 2. 问题改写与拆分

**入口**: `MultiQuestionRewriteService.rewriteWithSplit()`

```
用户原始问题: "打印机坏了咋整，顺便问下报销流程"
       │
       ▼
  术语归一化 (QueryTermMappingService)
  "打印机故障处理流程，报销流程"
       │
       ▼
  LLM 改写 + 拆分 (返回 JSON)
  {
    "rewrite": "打印机故障处理流程及报销流程",
    "sub_questions": [
      "打印机出现故障应如何处理？",
      "报销流程是什么？"
    ]
  }
```

**兜底策略**：
- 配置开关关闭 → 退化为本地归一化 + 规则拆分（按 `?？。；` 分割）
- LLM 调用失败 → 使用归一化后的问题兜底
- 模型返回的 JSON 解析失败 → 同上

### 3. 意图识别

**入口**: `IntentResolver.resolve()`

意图树是三级结构：**领域(Domain) → 类目(Category) → 话题(Topic)**

```
IT 支持 (Domain)
├── 硬件 (Category)
│   ├── 打印机 (Topic) → kind=KB, 关联知识库 collection
│   └── 网络设备 (Topic) → kind=KB
├── 软件 (Category)
│   └── ...
└── 查工单 (Topic) → kind=MCP, 关联 mcp_tool_id
```

**流程**：
1. 每个子问题并行送入 `IntentClassifier.classifyTargets()`
2. Classifier 基于意图节点的 description + examples 做语义匹配，返回 `List<NodeScore>`
3. 过滤置信度 < INTENT_MIN_SCORE 的结果
4. 全局裁剪：总意图数不超过 MAX_INTENT_COUNT，但保证每个子问题至少保留 1 个最高分意图

**意图类型**：
- `KB` — 走知识库检索
- `MCP` — 走 MCP 工具调用
- `SYSTEM` — 系统直答（如闲聊、打招呼）

### 4. 歧义引导

当检测到用户问题存在歧义（置信度不够、多意图冲突等），优先让用户补充信息：

```java
GuidanceDecision decision = guidanceService.detectAmbiguity(question, subIntents);
if (decision.isPrompt()) {
    callback.onContent(decision.getPrompt()); // "您是想了解...还是...？"
    callback.onComplete();
    return; // 不进入后续检索
}
```

### 5. 检索 + 重排序

这是最复杂的阶段，采用**多通道并行 + 后处理流水线**架构。

```
RetrievalEngine.retrieve()
  │
  ├── 对每个子问题并行构建上下文 (CompletableFuture)
  │   │
  │   ├── KB 链路:
  │   │   MultiChannelRetrievalEngine.retrieveKnowledgeChannels()
  │   │     ├── 意图定向通道 — 只在命中意图关联的 collection 中检索
  │   │     ├── 全局向量通道 — 跨所有 collection 的向量检索
  │   │     └── (可扩展更多 SearchChannel 实现)
  │   │   ↓
  │   │   后处理链 (SearchResultPostProcessor 按 order 串联)
  │   │     ├── DeduplicationPostProcessor (order=0) — 去重
  │   │     └── RerankPostProcessor (order=10) — Rerank 精排
  │   │
  │   └── MCP 链路:
  │       executeMcpTools() — 并行调用 MCP 工具
  │         ├── 参数提取 (MCPParameterExtractor)
  │         ├── 工具执行 (MCPToolExecutor)
  │         └── 结果收敛 (MCPResponse)
  │
  └── 汇总: KB 上下文 + MCP 上下文 + intentChunks
```

**Rerank 调用链**（详见上次分析）：
```
RerankPostProcessor → RoutingRerankService → ModelRoutingExecutor.executeWithFallback()
  ├── BaiLianRerankClient   (百炼 API)
  ├── VllmRerankClient      (vLLM /rerank 端点)
  └── OllamaRerankClient    (Ollama chat 模拟 rerank)
```

### 6. Prompt 组装

`RAGPromptService.buildStructuredMessages()` 将所有上下文组装成最终消息列表：

```
messages = [
  System("你是 XXX 助手，基于以下参考资料回答..."),    // 系统指令
  System("以下是会话摘要：..."),                        // 历史摘要（如果有）
  User("之前的问题..."), Assistant("之前的回答..."),     // 近 N 轮历史
  System("### 知识库参考资料\n{kbContext}\n### 工具数据\n{mcpContext}"),  // 检索上下文
  User("改写后的问题")                                  // 当前问题
]
```

### 7. 流式生成

```java
StreamCancellationHandle handle = llmService.streamChat(chatRequest, callback);
taskManager.bindHandle(taskId, handle);
```

- `StreamCallback` 负责把模型增量输出写入 SSE
- `ProbeBufferingCallback` 装饰器在首包阶段缓冲事件，确保模型切换时用户无感
- `StreamTaskManager` 持有 handle，供 `/rag/v3/stop` 接口取消生成
- 回调完成时自动把 assistant 回复落库到 `t_message` 表

## 三种分支路径

| 条件 | 走的路径 |
|------|---------|
| 所有意图都是 SYSTEM | 系统直答 — 跳过检索，直接用系统 Prompt + 历史 → LLM |
| 有 KB / MCP 意图，但检索结果为空 | 返回兜底文案 "未检索到与问题相关的文档内容" |
| 有 KB / MCP 意图，检索有结果 | 完整 RAG 链路 → Prompt 组装 → 流式生成 |

## 全链路追踪

每个关键环节都通过 `@RagTraceNode` 注解自动记录到 `t_rag_trace_node` 表：

| 节点名称 | 类型 | 对应方法 |
|----------|------|---------|
| query-rewrite-and-split | REWRITE | `MultiQuestionRewriteService.rewriteWithSplit()` |
| intent-resolve | INTENT | `IntentResolver.resolve()` |
| retrieval-engine | RETRIEVE | `RetrievalEngine.retrieve()` |
| multi-channel-retrieval | RETRIEVE_CHANNEL | `MultiChannelRetrievalEngine.retrieveKnowledgeChannels()` |
| llm-chat-routing | LLM_ROUTING | `RoutingLLMService.chat()` |
| bailian-chat / ollama-chat / vllm-chat | LLM_PROVIDER | 各 ChatClient 实现 |

前端 Trace 页面可以看到每个节点的状态（SUCCESS/ERROR/RUNNING）、耗时和错误信息。
