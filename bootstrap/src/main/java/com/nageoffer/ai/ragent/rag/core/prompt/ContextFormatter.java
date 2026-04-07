/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.prompt;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;

import java.util.List;
import java.util.Map;

/**
 * 上下文格式化器接口。
 * <p>
 * 用于把检索得到的结构化结果整理成适合拼进 Prompt 的文本块。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */public interface ContextFormatter {

    /**
     * 格式化知识库检索上下文。
     */
    String formatKbContext(List<NodeScore> kbIntents, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK);

    /**
     * 格式化 MCP 工具调用上下文。
     */
    String formatMcpContext(List<MCPResponse> responses, List<NodeScore> mcpIntents);
}
