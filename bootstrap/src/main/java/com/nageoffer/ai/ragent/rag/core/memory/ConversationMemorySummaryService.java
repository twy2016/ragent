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

package com.nageoffer.ai.ragent.rag.core.memory;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;

/**
 * 对话记忆摘要服务接口。
 * <p>
 * 负责把较长的多轮对话压缩成摘要消息，并在读取上下文时按需包装成适合模型消费的 system 消息。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */public interface ConversationMemorySummaryService {

    /**
     * 根据最新写入的消息判断是否需要触发摘要压缩。
     */
    void compressIfNeeded(String conversationId, String userId, ChatMessage message);

    /**
     * 读取当前会话最新的一条摘要消息。
     */
    ChatMessage loadLatestSummary(String conversationId, String userId);

    /**
     * 对摘要内容做统一装饰，便于直接注入模型上下文。
     */
    ChatMessage decorateIfNeeded(ChatMessage summary);
}
