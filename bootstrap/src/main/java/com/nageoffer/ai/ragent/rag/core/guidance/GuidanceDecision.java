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

package com.nageoffer.ai.ragent.rag.core.guidance;

import lombok.Getter;

/**
 * 引导式问答决策结果类
 * 用于表示是否需要向用户输出引导式问答提示
 * <p>
 * 它是歧义检测阶段的统一输出载体，上游只需根据 action 判断是继续检索还是直接返回引导提示。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */@Getter
public class GuidanceDecision {

    public enum Action {
        /**
         * 不触发引导，继续正常 RAG 链路。
         */
        NONE,
        /**
         * 触发引导，直接向用户返回补充确认文案。
         */
        PROMPT
    }

    private final Action action;
    private final String prompt;

    private GuidanceDecision(Action action, String prompt) {
        this.action = action;
        this.prompt = prompt;
    }

    public static GuidanceDecision none() {
        return new GuidanceDecision(Action.NONE, null);
    }

    /**
     * 创建一个“需要向用户展示引导提示”的决策结果。
     */
    public static GuidanceDecision prompt(String prompt) {
        return new GuidanceDecision(Action.PROMPT, prompt);
    }

    /**
     * 是否命中了引导提示分支。
     */
    public boolean isPrompt() {
        return action == Action.PROMPT;
    }
}
