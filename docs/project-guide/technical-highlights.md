# 技术难点与亮点分析

## 难点 1：多通道检索的并行与融合

**问题**：单一检索通道（如纯向量检索）存在召回不足的问题。用户问"订单号 20240101"，向量检索可能完全匹配不到。

**解决方案**：多通道并行 + 后处理流水线

```
意图定向通道 ──┐
               ├──→ 去重 → Rerank → 最终 TopK
全局向量通道 ──┘
```

**难在哪里**：
- 不同通道返回的 chunk 可能有重叠，需要跨通道去重
- 不同通道的分数维度不同（BM25 分数 vs 余弦相似度），不能简单比较
- Rerank 模型本身也可能不可用，需要降级策略
- 多通道并行需要线程池隔离，避免慢通道拖累快通道

**代码位置**：
- `MultiChannelRetrievalEngine` — 通道调度
- `SearchChannel` 接口 — 可插拔通道
- `SearchResultPostProcessor` — 后处理链

---

## 难点 2：模型路由与首包探测

**问题**：流式场景下，如果模型 A 推了几个 token 后挂了，切到模型 B 会导致用户收到"半截回答"。

**解决方案**：首包探测 + 缓冲装饰器

```java
// ProbeBufferingCallback 在首包阶段缓冲所有事件
// 首包成功 → flush 缓冲
// 首包失败 → 丢弃缓冲 → 切换模型 → 重新开始
```

**难在哪里**：
- 需要精确定义"首包成功"的判断条件（收到第一个有效 content token）
- 缓冲阶段的内存管理（如果模型很快推了大量数据）
- 需要线程安全（模型推送线程 vs 超时检测线程 vs 用户取消线程）
- 降级后的模型需要完整重跑，不能从中间续接

**代码位置**：
- `ProbeBufferingCallback` — 装饰器实现
- `FirstPacketAwaiter` — 首包等待器
- `RoutingLLMService` — 路由编排

---

## 难点 3：会话记忆的 Token 成本控制

**问题**：20 轮对话全塞给模型，Token 成本爆炸；只带最近 2 轮，上下文丢失导致答非所问。

**解决方案**：滑动窗口 + LLM 自动摘要压缩

```
轮次 1-5: 完整保留
轮次 6+:  触发异步摘要
         → LLM 生成摘要
         → 以 System 消息注入
         → 原始消息归档

最终发送给模型:
[System(摘要), User(最近问题1), Assistant(最近回答1), ..., User(当前问题)]
```

**难在哪里**：
- 摘要本身也要调 LLM，是个异步操作，不能阻塞当前问答
- 摘要质量直接影响后续回答质量，需要精心设计 Prompt
- 摘要需要持久化（`t_conversation_summary`），避免每次重新生成
- 何时触发摘要（消息数阈值 vs Token 数阈值）是工程取舍

**代码位置**：
- `DefaultConversationMemoryService`
- `JdbcConversationMemorySummaryService`
- `ConversationMemoryStore`

---

## 难点 4：意图识别与歧义引导

**问题**：用户问"怎么申请"，不知道是申请报销、申请权限还是申请设备。

**解决方案**：树形意图体系 + 置信度阈值 + 歧义引导

```
意图树: Domain → Category → Topic
每个节点有 description + examples

分类流程:
1. 用户问题 vs 所有叶子节点做语义匹配
2. 返回 TopN 候选 + 置信度分数
3. 置信度 < 阈值 → 触发引导：
   "您是想了解 A 还是 B？请补充更多信息"
```

**难在哪里**：
- 意图树的粒度设计：太粗检索不准，太细维护成本高
- 多子问题场景下意图总数控制（capTotalIntents 保底策略）
- 歧义检测的精度：不能太灵敏（总是追问）也不能太迟钝（错误前提下检索）

**代码位置**：
- `IntentResolver` — 意图解析入口
- `IntentClassifier` — 语义匹配分类器
- `IntentGuidanceService` — 歧义引导决策

---

## 难点 5：分布式排队限流

**问题**：多个用户同时提问，模型并发有上限，如何公平排队且跨实例协调？

**解决方案**：Redis ZSET 排队 + Lua 原子出队 + Pub/Sub 通知

```
请求入队 → ZSET(score=时间戳)
         → Lua 脚本原子判断是否在队头窗口内
         → 信号量控制最大并发数
         → 许可自动过期（防死锁）

跨实例协调:
         → Pub/Sub 广播唤醒
         → 本地合并通知（防惊群）

用户等待期间:
         → SSE 推送排队状态（"前面还有 N 人"）
```

**难在哪里**：
- Lua 脚本需要保证原子性：判断队头位置 + 出队 + 获取信号量 一步完成
- 许可过期回收：某个实例宕机不能永久占住信号量
- 排队超时自动踢出：避免用户已离开但占着队列位置
- 惊群效应：一个许可释放不能唤醒所有等待者

**代码位置**：
- `@ChatRateLimit` 切面
- Redis Lua 脚本（排队/出队/释放）

---

## 难点 6：全链路 Trace 的跨线程透传

**问题**：RAG 链路大量使用异步线程池（8 个专用线程池），traceId 和 userId 跨线程会丢失。

**解决方案**：TTL (Transmittable Thread Local) + 切面自动采集

```
主线程设置 traceId → TTL 自动传播到子线程
                   → TtlExecutors.wrap(threadPool) 包装所有线程池
                   → @RagTraceNode 切面自动在子方法入口/出口记录
```

**8 个线程池**：
| 线程池 | 用途 |
|--------|------|
| ragContextThreadPoolExecutor | 子问题级上下文构建 |
| mcpBatchThreadPoolExecutor | MCP 工具批量执行 |
| multiChannelThreadPoolExecutor | 多通道检索 |
| innerSearchThreadPoolExecutor | 通道内部检索 |
| intentClassifyThreadPoolExecutor | 意图分类 |
| memorySummaryThreadPoolExecutor | 记忆摘要压缩 |
| streamAsyncExecutor | 模型流式输出 |
| chatEntryExecutor | 对话入口 |

---

## 亮点总结

| 亮点 | 说明 |
|------|------|
| 面向接口的扩展点 | 新增检索通道/后处理器/MCP工具/入库节点/模型供应商，只需加实现类 |
| 三态熔断器 | 不依赖第三方库，自研的 CLOSED/OPEN/HALF_OPEN 状态机 |
| 首包探测 | 流式场景下的无损降级，装饰器模式优雅实现 |
| AOP 全链路追踪 | `@RagTraceNode` 一个注解搞定，业务代码零侵入 |
| Pipeline 节点编排 | 入库流程可配置、可条件执行、可扩展 |
| 排队式限流 | 不是简单拒绝，而是公平排队 + 实时状态推送 |
