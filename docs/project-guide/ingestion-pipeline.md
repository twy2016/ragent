# 文档入库 ETL 流水线

## 概述

文档从上传到可检索，经过一条基于**节点编排**的 Pipeline。Pipeline 的定义存储在数据库中（`t_ingestion_pipeline` + `t_ingestion_pipeline_node`），支持灵活配置节点顺序、条件执行和参数自定义。

## 执行流程

```
文档上传 / URL 抓取
       │
       ▼
┌─────────────────────────────────────────────────────┐
│  FetcherNode    — 从 S3/URL 获取原始文件             │
│  ↓                                                   │
│  ParserNode     — Tika/Markdown 解析为纯文本         │
│  ↓                                                   │
│  EnhancerNode   — (可选) LLM 增强文本质量            │
│  ↓                                                   │
│  ChunkerNode    — 按策略切分为多个 chunk              │
│  ↓                                                   │
│  EnricherNode   — (可选) 为 chunk 追加元数据          │
│  ↓                                                   │
│  IndexerNode    — Embedding 向量化 + 写入 Milvus      │
└─────────────────────────────────────────────────────┘
       │
       ▼
  文档可检索
```

## 核心类

### IngestionEngine (`ingestion/engine/IngestionEngine.java`)

流水线执行引擎，职责：

1. **构建节点映射** — 从 `PipelineDefinition` 解析所有 `NodeConfig`
2. **验证流水线** — 检测环路、断裂引用
3. **找到起始节点** — 没有被任何节点的 nextNodeId 引用的就是起点
4. **链式执行** — while 循环沿 `nextNodeId` 链逐个执行

```java
while (currentNodeId != null) {
    NodeResult result = executeNode(context, config);
    if (!result.isSuccess()) {
        context.setStatus(FAILED);    // 任何节点失败，整条 Pipeline 终止
        break;
    }
    if (!result.isShouldContinue()) {
        break;                        // 节点主动停止（如条件不满足）
    }
    currentNodeId = config.getNextNodeId();
}
```

### IngestionNode 接口 (`ingestion/node/IngestionNode.java`)

所有节点的统一接口：

```java
public interface IngestionNode {
    String getNodeType();
    NodeResult execute(IngestionContext context, NodeConfig config);
}
```

### 节点实现

| 节点 | 类型标识 | 职责 |
|------|---------|------|
| `FetcherNode` | fetcher | 从 S3 或 URL 下载原始文件到本地临时目录 |
| `ParserNode` | parser | 使用 Tika 或 Markdown 解析器提取纯文本 |
| `ChunkerNode` | chunker | 按配置的策略（固定大小/结构感知）切分文本 |
| `IndexerNode` | indexer | 调用 Embedding 服务向量化，写入 Milvus + 关系库 |

### IngestionContext

流水线的上下文对象，在节点间传递：

```java
class IngestionContext {
    IngestionStatus status;          // RUNNING / COMPLETED / FAILED
    String error;                    // 失败时的错误信息
    DocumentSource source;           // 文档来源（文件路径/URL）
    StructuredDocument document;     // 解析后的结构化文档
    List<VectorChunk> chunks;        // 切分后的 chunk 列表
    List<NodeLog> logs;              // 每个节点的执行日志
    Map<String, Object> metadata;    // 扩展元数据
}
```

## 分块策略

支持两种策略，通过 `ChunkingStrategyFactory` 创建：

| 策略 | 类 | 描述 |
|------|-----|------|
| `FIXED_SIZE` | `FixedSizeTextChunker` | 按固定字符数切分，支持重叠窗口 |
| `TEXT_BOUNDARY` | `StructureAwareTextChunker` | 感知标题/段落边界，保持语义完整 |

配置示例（存储在 `t_knowledge_document.chunk_config` JSONB 字段）：
```json
{
  "chunkSize": 500,
  "overlap": 50,
  "separator": "\n\n"
}
```

## 文档解析

`DocumentParserSelector` 根据文件类型选择解析器：

| 类型 | 解析器 | 说明 |
|------|--------|------|
| `.md` | `MarkdownDocumentParser` | 原生 Markdown 解析，保留结构 |
| 其他（PDF/DOCX/PPT 等） | `TikaDocumentParser` | Apache Tika 通用解析 |

## 任务管理

每次入库创建一条 `t_ingestion_task` 记录：

- 状态流转：`PENDING → RUNNING → COMPLETED / FAILED`
- 每个节点的执行记录写入 `t_ingestion_task_node`（耗时、状态、输出）
- 节点日志以 JSON 序列化存储在 `t_ingestion_task.logs_json`

## 定时刷新

对于 URL 类型的文档，支持定时自动刷新：

```
t_knowledge_document_schedule
  ├── cron_expr    — Cron 表达式
  ├── next_run_time — 下次执行时间
  ├── lock_owner   — 分布式锁持有者
  └── lock_until   — 锁过期时间
```

`ScheduleRefreshProcessor` 定期扫描到期任务，通过 `ScheduleLockManager` 抢占分布式锁后执行：
1. 下载 URL 内容
2. 通过 ETag / Content-Hash 判断是否有变更
3. 有变更则重走 Pipeline，更新向量索引

## 扩展新节点

实现 `IngestionNode` 接口并注册为 Spring Bean 即可：

```java
@Component
public class MyCustomNode implements IngestionNode {
    @Override
    public String getNodeType() { return "my_custom"; }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // 自定义逻辑
        return NodeResult.success("处理完成");
    }
}
```

然后在 Pipeline 配置中添加该节点类型，无需修改引擎代码。
