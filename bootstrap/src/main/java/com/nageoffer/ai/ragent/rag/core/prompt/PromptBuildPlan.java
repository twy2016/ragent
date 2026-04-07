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

import lombok.Builder;
import lombok.Data;

/**
 * Prompt 构建计划。
 * <p>
 * 用于描述一次 Prompt 组装最终应采用的场景和基础模板选择结果。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */@Data
@Builder
public class PromptBuildPlan {

    private PromptScene scene;

    private String baseTemplate;

    private String mcpContext;

    private String kbContext;

    private String question;
}
