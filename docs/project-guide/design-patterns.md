# 设计模式与对应代码

## 策略模式 (Strategy)

**场景**：多种检索通道、后处理器、MCP 工具，需要可插拔替换。

```java
// 定义策略接口
public interface SearchChannel {
    List<RetrievedChunk> search(SearchContext context);
}

// 多个实现
@Component class IntentDirectedChannel implements SearchChannel { ... }
@Component class GlobalVectorChannel implements SearchChannel { ... }

// 使用者不关心具体实现
private final List<SearchChannel> channels; // Spring 自动注入所有实现
```

同样的模式还用于：
- `SearchResultPostProcessor` — 后处理器
- `MCPToolExecutor` — MCP 工具
- `IngestionNode` — 入库节点
- `ChatClient` / `RerankClient` / `EmbeddingClient` — 模型客户端

---

## 工厂模式 (Factory)

**场景**：根据配置动态创建分块策略。

```java
// ChunkingStrategyFactory
public class ChunkingStrategyFactory {
    public static ChunkingStrategy create(ChunkingMode mode, ...) {
        return switch (mode) {
            case FIXED_SIZE -> new FixedSizeTextChunker(...);
            case TEXT_BOUNDARY -> new StructureAwareTextChunker(...);
        };
    }
}
```

同样的模式还用于：
- `StreamCallbackFactory` — 创建不同场景的流式回调
- `DocumentParserSelector` — 根据文件类型选择解析器

---

## 模板方法 (Template Method)

**场景**：所有 OpenAI 兼容的 Chat 客户端共享相同的请求构建和 SSE 解析流程。

```java
// 基类定义骨架
public abstract class AbstractOpenAIStyleChatClient implements ChatClient {
    // 模板方法：构建请求 → 发送 → 解析响应
    public String chat(ChatRequest request, ModelTarget target) {
        Request httpRequest = buildRequest(request, target);   // 公共逻辑
        Response response = httpClient.newCall(httpRequest).execute();
        return parseResponse(response);                        // 公共逻辑
    }

    // 子类只需提供差异化部分
    protected abstract String resolveUrl(ModelTarget target);
    protected abstract void customizeRequest(JsonObject body, ModelTarget target);
}

// 子类
class BaiLianChatClient extends AbstractOpenAIStyleChatClient { ... }
class OllamaChatClient extends AbstractOpenAIStyleChatClient { ... }
class VllmChatClient extends AbstractOpenAIStyleChatClient { ... }
```

---

## 装饰器模式 (Decorator)

**场景**：在不修改原有 StreamCallback 的前提下，增加首包探测能力。

```java
// 原始回调
public interface StreamCallback {
    void onContent(String content);
    void onComplete();
    void onError(Throwable error);
}

// 装饰器：包装原始回调，增加缓冲和探测逻辑
public class ProbeBufferingCallback implements StreamCallback {
    private final StreamCallback delegate;     // 被装饰的原始回调
    private final List<String> buffer;         // 首包缓冲区

    @Override
    public void onContent(String content) {
        if (probing) {
            buffer.add(content);               // 首包阶段：缓冲
            if (isFirstValidToken(content)) {
                flush();                       // 首包成功：刷出缓冲
            }
        } else {
            delegate.onContent(content);       // 正常阶段：直接透传
        }
    }
}
```

---

## 责任链模式 (Chain of Responsibility)

**场景 1**：检索后处理器链 — 去重 → Rerank，按 order 串联执行。

```java
public interface SearchResultPostProcessor {
    int getOrder();
    boolean isEnabled(SearchContext context);
    List<RetrievedChunk> process(List<RetrievedChunk> chunks, ...);
}

// 按 order 排序后串联执行
processors.stream()
    .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
    .filter(p -> p.isEnabled(context))
    .forEach(p -> chunks = p.process(chunks, ...));
```

**场景 2**：模型降级链 — 候选1 失败 → 候选2 → 候选3 → 全部失败

---

## 注册表模式 (Registry)

**场景**：MCP 工具自动发现与注册。

```java
@Component
public class DefaultMCPToolRegistry implements MCPToolRegistry {
    private final Map<String, MCPToolExecutor> executors;

    // 构造器注入所有 MCPToolExecutor Bean
    public DefaultMCPToolRegistry(List<MCPToolExecutor> executorList) {
        this.executors = executorList.stream()
            .collect(toMap(e -> e.getToolDefinition().getId(), identity()));
    }

    public Optional<MCPToolExecutor> getExecutor(String toolId) {
        return Optional.ofNullable(executors.get(toolId));
    }
}
```

新增工具只需实现 `MCPToolExecutor` 接口并标记 `@Component`，零配置生效。

同样的模式还用于：
- `IngestionEngine` — 按 nodeType 注册所有 `IngestionNode`
- `RoutingRerankService` — 按 provider 注册所有 `RerankClient`

---

## 观察者模式 (Observer)

**场景**：流式事件通知 — 模型推送 token 事件给多个监听者。

```java
public interface StreamCallback {
    void onContent(String content);    // 增量内容事件
    void onThinking(String thinking);  // 深度思考事件
    void onComplete();                 // 完成事件
    void onError(Throwable error);     // 错误事件
}
```

不同场景创建不同的 Callback 实现：
- Chat 场景：写 SSE + 落库 + 通知任务管理器
- 摘要场景：只收集结果

---

## AOP 横切关注点

**场景**：链路追踪和限流与业务代码完全解耦。

```java
// 一个注解搞定 Trace 记录
@RagTraceNode(name = "intent-resolve", type = "INTENT")
public List<SubQuestionIntent> resolve(RewriteResult rewriteResult) {
    // 纯业务代码，无任何 trace 逻辑
}

// 切面自动处理
@Around("@annotation(traceNode)")
public Object aroundNode(ProceedingJoinPoint joinPoint, RagTraceNode traceNode) {
    // 记录开始 → 执行 → 记录结束/错误
}
```

同样用于：
- `@ChatRateLimit` — 排队式限流
- `@IdempotentSubmit` — 提交幂等
- `@IdempotentConsume` — 消费幂等
