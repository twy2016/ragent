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

import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * KB Prompt 规划结果。
 * <p>
 * 主要回答两个问题：
 * 1. 哪些意图在真正检索到文档后应该被保留；
 * 2. 是否存在可直接采用的节点级基础模板。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */@Data
@RequiredArgsConstructor
@AllArgsConstructor
@Builder
public class PromptPlan {

    /**
     * 剔除无检索结果后保留的意图
     */
    private List<NodeScore> retainedIntents;

    /**
     * 选用的基模板（单意图且有模板才会有值，否则为 null 表示用默认模板）
     */
    private String baseTemplate;
}
