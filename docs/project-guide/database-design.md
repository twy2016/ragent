# 数据库设计总览

## 表清单

共 **17 张业务表**，按业务域分组：

### 用户与会话域

| 表名 | 用途 | 关键字段 |
|------|------|---------|
| `t_user` | 系统用户 | username, password, role(admin/user) |
| `t_conversation` | 会话列表 | conversation_id, user_id, title |
| `t_conversation_summary` | 会话摘要（压缩后的历史） | conversation_id, last_message_id, content |
| `t_message` | 消息记录 | conversation_id, role(user/assistant), content, thinking_content |
| `t_message_feedback` | 消息反馈（点赞/点踩） | message_id, vote(1/-1), reason |
| `t_sample_question` | 首页示例问题 | title, question |

### 知识库域

| 表名 | 用途 | 关键字段 |
|------|------|---------|
| `t_knowledge_base` | 知识库 | name, embedding_model, collection_name |
| `t_knowledge_document` | 文档 | kb_id, doc_name, file_url, file_type, status, chunk_strategy, chunk_config(JSONB), pipeline_id |
| `t_knowledge_chunk` | 文档分块 | kb_id, doc_id, chunk_index, content, content_hash |
| `t_knowledge_document_chunk_log` | 分块处理日志 | doc_id, status, extract/chunk/embed/persist_duration |
| `t_knowledge_document_schedule` | 定时刷新配置 | doc_id, cron_expr, next_run_time, lock_owner, last_content_hash |
| `t_knowledge_document_schedule_exec` | 定时刷新执行记录 | schedule_id, status, content_hash, etag |

### 向量存储

| 表名 | 用途 | 关键字段 |
|------|------|---------|
| `t_knowledge_vector` | pgvector 向量表 | content, metadata(JSONB), embedding(vector(1536)) |

主存储使用 **Milvus**，此表为 pgvector 备选方案。

### 意图与查询域

| 表名 | 用途 | 关键字段 |
|------|------|---------|
| `t_intent_node` | 意图树节点 | intent_code, name, level(0/1/2), parent_code, kind(0=KB/1=SYSTEM/2=MCP), mcp_tool_id, collection_name, prompt_template |
| `t_query_term_mapping` | 术语归一化映射 | source_term → target_term, match_type(精确/模糊) |

### 链路追踪域

| 表名 | 用途 | 关键字段 |
|------|------|---------|
| `t_rag_trace_run` | 链路根记录 | trace_id, conversation_id, task_id, status(RUNNING/SUCCESS/ERROR), duration_ms |
| `t_rag_trace_node` | 链路节点记录 | trace_id, node_id, parent_node_id, depth, node_type, node_name, status, error_message |

### 入库流水线域

| 表名 | 用途 | 关键字段 |
|------|------|---------|
| `t_ingestion_pipeline` | 流水线定义 | name, description |
| `t_ingestion_pipeline_node` | 流水线节点配置 | pipeline_id, node_id, node_type, next_node_id, settings_json(JSONB), condition_json(JSONB) |
| `t_ingestion_task` | 入库任务 | pipeline_id, source_type, status, logs_json(JSONB) |
| `t_ingestion_task_node` | 任务节点执行记录 | task_id, node_type, status, duration_ms, output_json |

## ER 关系概览

```
t_user ──1:N──→ t_conversation ──1:N──→ t_message
                      │                      │
                      └──1:1──→ t_conversation_summary
                                             │
                                      t_message_feedback

t_knowledge_base ──1:N──→ t_knowledge_document ──1:N──→ t_knowledge_chunk
                                  │
                                  ├──1:1──→ t_knowledge_document_schedule
                                  │              └──1:N──→ t_knowledge_document_schedule_exec
                                  └──1:N──→ t_knowledge_document_chunk_log

t_ingestion_pipeline ──1:N──→ t_ingestion_pipeline_node
       │
       └──1:N──→ t_ingestion_task ──1:N──→ t_ingestion_task_node

t_rag_trace_run ──1:N──→ t_rag_trace_node

t_intent_node (树形自关联: parent_code → intent_code)
```

## 设计要点

1. **所有表使用 Snowflake ID** — `VARCHAR(20)` 主键，分布式友好
2. **逻辑删除** — `deleted` 字段（0=正常，1=删除）
3. **审计字段** — `create_time` / `update_time` / `created_by` / `updated_by`
4. **JSONB 灵活配置** — Pipeline 节点配置、分块策略配置、任务日志等使用 JSONB 存储
5. **分布式锁** — 定时刷新使用 `lock_owner` + `lock_until` 实现乐观锁
6. **内容变更检测** — 通过 `content_hash` / `etag` / `last_modified` 避免无意义的重复入库
