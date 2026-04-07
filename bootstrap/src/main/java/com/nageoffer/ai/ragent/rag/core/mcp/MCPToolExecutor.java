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

package com.nageoffer.ai.ragent.rag.core.mcp;

/**
 * MCP 工具执行器接口
 * <p>
 * 每个执行器对应一个具体工具，同时负责暴露工具定义和执行该工具请求。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */public interface MCPToolExecutor {

    /**
     * 获取工具定义
     *
     * @return 工具元信息
     */
    MCPTool getToolDefinition();

    /**
     * 执行工具调用
     *
     * @param request MCP 请求
     * @return MCP 响应
     */
    MCPResponse execute(MCPRequest request);

    /**
     * 工具 ID（快捷方法）
     * <p>
     * 默认从工具定义中读取，保证注册表和执行逻辑共用同一个唯一标识。
     */
    default String getToolId() {
        return getToolDefinition().getToolId();
    }

    /**
     * 是否支持该请求
     * 默认只检查 toolId 是否匹配
     * <p>
     * 若执行器需要更复杂的能力协商，可以自行覆写。
     */
    default boolean supports(MCPRequest request) {
        return getToolId().equals(request.getToolId());
    }
}
