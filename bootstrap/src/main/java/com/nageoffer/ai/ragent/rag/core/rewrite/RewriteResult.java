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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import java.util.List;

/**
 * 查询改写结果。
 * <p>
 * 同时保存：
 * 1. 改写后的主问题；
 * 2. 拆分得到的子问题列表。
 * <p>
 * 后续意图识别既会参考 rewrittenQuestion，也会优先按 subQuestions 逐个分类。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */public record RewriteResult(String rewrittenQuestion, List<String> subQuestions) {

}
