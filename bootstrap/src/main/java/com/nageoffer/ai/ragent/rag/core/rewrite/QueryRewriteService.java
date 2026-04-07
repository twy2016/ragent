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

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;

import java.util.List;

/**
 * 用户查询改写：将自然语言问题改写成适合 RAG 检索的查询语句
 * <p>
 * 这是改写链路的统一抽象，允许不同实现选择是否支持多问句拆分以及是否利用会话历史。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */public interface QueryRewriteService {

    /**
     * 将用户问题改写为适合向量 / 关键字检索的简洁查询
     *
     * @param userQuestion 原始用户问题
     * @return 改写后的检索查询（如果改写失败，则回退原问题）
     */
    String rewrite(String userQuestion);

    /**
     * 可选：改写 + 拆分多问句
     * 默认实现仅返回改写结果并将其作为单个子问题
     * <p>
     * 适合没有专门拆分能力的实现类直接复用。
     */
    default RewriteResult rewriteWithSplit(String userQuestion) {
        String rewritten = rewrite(userQuestion);
        return new RewriteResult(rewritten, List.of(rewritten));
    }

    /**
     * 可选：改写 + 拆分多问句，支持会话历史
     * 默认实现忽略历史，回退到基础改写逻辑
     * <p>
     * 只有当具体实现覆写该方法时，history 才会真正参与改写决策。
     */
    default RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
        return rewriteWithSplit(userQuestion);
    }
}
