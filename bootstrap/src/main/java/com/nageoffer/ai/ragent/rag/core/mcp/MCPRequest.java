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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 调用请求
 * <p>
 * 描述一次工具调用所需的全部输入，包括工具标识、会话上下文和结构化参数。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPRequest {

    /**
     * 要调用的工具 ID
     */
    private String toolId;

    /**
     * 用户 ID（用于权限校验和个人数据查询）
     */
    private String userId;

    /**
     * 会话 ID（可选，用于上下文关联）
     */
    private String conversationId;

    /**
     * 原始用户问题
     */
    private String userQuestion;

    /**
     * 调用参数
     */
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();

    /**
     * 添加参数
     * <p>
     * 适合在构建后按需补充动态参数。
     */
    public void addParameter(String key, Object value) {
        if (this.parameters == null) {
            this.parameters = new HashMap<>();
        }
        this.parameters.put(key, value);
    }

    /**
     * 获取参数
     * <p>
     * 由调用方负责保证泛型类型和真实存储类型一致。
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return null;
        }
        return (T) value;
    }

    /**
     * 获取字符串参数
     * <p>
     * 常用于读取通用的字符串型工具入参。
     */
    public String getStringParameter(String key) {
        Object value = parameters.get(key);
        return value != null ? value.toString() : null;
    }
}
