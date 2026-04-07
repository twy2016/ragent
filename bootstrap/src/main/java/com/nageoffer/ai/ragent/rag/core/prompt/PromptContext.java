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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Prompt 构建上下文。
 * <p>
 * 它是检索阶段与 Prompt 编排阶段之间的桥梁，汇总了问题文本、KB 证据、MCP 动态数据和命中的意图信息。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */@Data
@Builder
public class PromptContext {

    private String question;

    private String mcpContext;

    private String kbContext;

    private List<NodeScore> mcpIntents;

    private List<NodeScore> kbIntents;

    private Map<String, List<RetrievedChunk>> intentChunks;

    public boolean hasMcp() {
        return StrUtil.isNotBlank(mcpContext);
    }

    /**
     * 当前上下文中是否存在知识库证据。
     */
    public boolean hasKb() {
        return StrUtil.isNotBlank(kbContext);
    }
}
