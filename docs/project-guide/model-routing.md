# 模型路由、健康检查与容错降级

## 概述

生产环境不可能只依赖一个模型供应商。Ragent 的模型路由机制解决的核心问题是：**一个模型挂了，自动切到下一个，业务层无感知**。

这套机制同时覆盖 Chat、Embedding、Rerank 三种模型能力。

## 架构图

```
业务层 (RAGChatService / RetrievalEngine)
   │
   ▼
路由服务 (RoutingLLMService / RoutingEmbeddingService / RoutingRerankService)
   │
   ├── ModelSelector.selectXxxCandidates()  → 按优先级 + 健康状态排序候选列表
   │
   ▼
ModelRoutingExecutor.executeWithFallback()
   │
   ├── 候选1 (优先级最高) ──成功──→ 返回结果
   │     │ 失败
   │     ▼
   ├── 候选2 ──成功──→ 返回结果
   │     │ 失败
   │     ▼
   ├── 候选3 ──成功──→ 返回结果
   │     │ 失败
   │     ▼
   └── 全部失败 → 抛 RemoteException
```

## 核心类

### ModelRoutingExecutor (`infra-ai/.../model/ModelRoutingExecutor.java`)

通用的降级执行骨架，不关心具体是 Chat/Embedding/Rerank，通过函数式接口注入：

```java
public <C, T> T executeWithFallback(
    ModelCapability capability,      // CHAT / EMBEDDING / RERANK
    List<ModelTarget> targets,       // 候选列表（已按优先级排序）
    Function<ModelTarget, C> clientResolver,  // 从 target 解析出具体 client
    ModelCaller<C, T> caller         // 实际调用逻辑
)
```

执行逻辑：
1. 遍历候选 targets
2. 解析 client，null 则 skip
3. `healthStore.allowCall(targetId)` — OPEN 状态直接跳过
4. 调用成功 → `markSuccess()` → 返回
5. 调用异常 → `markFailure()` + warn 日志 → 尝试下一个
6. 全部失败 → 抛出 `RemoteException`，附带最后一次异常信息

### ModelHealthStore (`infra-ai/.../model/ModelHealthStore.java`)

每个模型独立维护健康状态，实现了经典的**三态熔断器**：

```
    ┌─────────┐
    │ CLOSED  │ ← 正常状态，所有请求通过
    └────┬────┘
         │ 连续失败次数 ≥ 阈值
         ▼
    ┌─────────┐
    │  OPEN   │ ← 熔断状态，所有请求被拒绝
    └────┬────┘
         │ 冷却期过后
         ▼
    ┌─────────┐
    │HALF_OPEN│ ← 半开状态，放行少量探测请求
    └────┬────┘
         │
    ┌────┴────┐
    │         │
  成功      失败
    │         │
    ▼         ▼
 CLOSED     OPEN
```

关键方法：
- `allowCall(modelId)` — CLOSED 直接放行；OPEN 判断是否到冷却期；HALF_OPEN 原子抢占探测资格
- `markSuccess(modelId)` — 重置失败计数，HALF_OPEN → CLOSED
- `markFailure(modelId)` — 累加失败计数，达阈值 → OPEN

### ModelSelector (`infra-ai/.../model/ModelSelector.java`)

从 YAML 配置中读取所有模型候选，按能力类型和优先级排序：

```java
List<ModelTarget> selectChatCandidates();
List<ModelTarget> selectEmbeddingCandidates();
List<ModelTarget> selectRerankCandidates();
```

排序规则：
1. 先按 `priority` 字段排序（数字越小优先级越高）
2. 被 healthStore 标记为 OPEN 的排到最后（但不完全排除，因为可能即将到冷却期）

### ModelTarget

```java
public record ModelTarget(
    String id,                           // 唯一标识: "provider:modelId"
    AIModelProperties.ModelCandidate candidate  // 包含 provider、model、url、priority 等
) {}
```

## 配置示例

```yaml
ai:
  model:
    providers:
      bailian:
        api-key: ${BAILIAN_API_KEY}
        url: https://dashscope.aliyuncs.com
      ollama:
        url: http://localhost:11434
      vllm:
        url: http://localhost:8000
    candidates:
      chat:
        - provider: bailian
          model: qwen-plus
          priority: 1
        - provider: vllm
          model: Qwen/Qwen2.5-7B
          priority: 2
        - provider: ollama
          model: qwen2.5:7b
          priority: 3
      rerank:
        - provider: bailian
          model: gte-rerank
          priority: 1
        - provider: vllm
          model: BAAI/bge-reranker-v2-m3
          priority: 2
      embedding:
        - provider: bailian
          model: text-embedding-v3
          priority: 1
```

## 流式场景的特殊处理：首包探测

流式 Chat 有一个特殊问题：如果模型 A 已经推了几个 token 给用户后才报错，切换到模型 B 会导致用户收到"半截的脏数据"。

**解决方案**：`ProbeBufferingCallback`（装饰器模式）

```
用户 → SSE Emitter
            ↑
  ProbeBufferingCallback
    ├── 首包阶段：缓冲所有事件，不写 SSE
    │     │
    │   收到第一个有效 token（首包成功）
    │     │
    │     ▼
    │   Flush 缓冲 → 后续直接透传
    │
    └── 首包阶段异常 → 丢弃缓冲 → 切换到下一个模型 → 重新开始
```

这样确保用户只会看到一个模型的完整输出，永远不会收到混合的脏数据。

## 能力矩阵

| Provider | Chat | Embedding | Rerank |
|----------|------|-----------|--------|
| 百炼 (BaiLian) | `BaiLianChatClient` | - | `BaiLianRerankClient` |
| Ollama | `OllamaChatClient` | `OllamaEmbeddingClient` | `OllamaRerankClient`* |
| vLLM | `VllmChatClient` | `VllmEmbeddingClient` | `VllmRerankClient` |
| SiliconFlow | `SiliconFlowChatClient` | `SiliconFlowEmbeddingClient` | - |

*Ollama 没有原生 Rerank 端点，通过 Chat Completions 接口让模型返回 JSON 排序结果模拟实现。

## 扩展新供应商

1. 在 `infra-ai` 层实现 `ChatClient` / `EmbeddingClient` / `RerankClient` 接口
2. 注册为 Spring Bean
3. 在 YAML 的 `candidates` 列表中添加配置

无需修改路由或健康检查代码。
