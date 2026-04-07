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

package com.nageoffer.ai.ragent.rag.dto;

import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;

/**
 * 意图候选及其子问题索引
 *
 * @param subQuestionIndex 子问题下标
 * @param nodeScore        意图候选分数
 * <p>
 * 主要用于全局意图裁剪阶段，把不同子问题的候选放到同一个排序空间里比较。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */public record IntentCandidate(int subQuestionIndex, NodeScore nodeScore) {
}
