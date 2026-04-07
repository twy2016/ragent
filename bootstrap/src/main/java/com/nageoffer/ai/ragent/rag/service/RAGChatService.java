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

package com.nageoffer.ai.ragent.rag.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话服务接口
 * 对外暴露流式问答与任务停止能力，屏蔽控制器层之外的实现细节
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */public interface RAGChatService {

    /**
     * 发起一次 SSE 流式问答
     * <p>
     * 该方法是 RAG 对话链路的统一入口，调用方只需传入用户问题、可选会话 ID 以及 SSE 发射器，
     * 具体的上下文加载、问题改写、意图识别、知识检索、Prompt 组装和模型流式输出均由实现类负责。
     * <p>
     * 典型执行流程如下：
     * 1. 校验并补齐会话标识，为本次流式任务生成任务 ID；
     * 2. 将用户问题写入会话记忆，并加载历史对话作为上下文；
     * 3. 对原始问题进行改写与拆分，得到更适合检索和推理的子问题；
     * 4. 基于改写结果识别意图，判断是系统类问答、知识库检索还是 MCP 工具场景；
     * 5. 若识别到歧义且需要用户补充信息，则直接通过 SSE 返回引导内容并结束；
     * 6. 若命中纯系统问答场景，则跳过检索，直接构造 Prompt 发起模型流式回答；
     * 7. 若需要检索，则并行汇总知识库和工具上下文，构造增强后的 Prompt；
     * 8. 调用底层大模型服务进行流式输出，并将增量内容持续写入 SSE 通道；
     * 9. 在流式结束、异常中断或用户主动停止时，由实现类负责资源清理和状态收敛。
     * <p>
     * 行为约定：
     * 1. 该方法面向 SSE 异步输出设计，正常情况下不会通过返回值携带业务结果；
     * 2. {@code conversationId} 为空时，通常由实现类创建新的会话 ID；
     * 3. {@code deepThinking} 用于控制底层模型是否启用更强的思考模式；
     * 4. 实现类可在入口处叠加限流、排队、链路追踪等横切能力，但不改变该方法的对外语义。
     *
     * @param question       用户问题
     * @param conversationId 会话 ID（可选，空时创建新会话）
     * @param deepThinking   是否开启深度思考模式
     * @param emitter        SSE 发射器
     */
    void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter);

    /**
     * 停止指定任务 ID 的流式会话
     *
     * @param taskId 任务 ID
     */
    void stopTask(String taskId);
}
