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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.aop.ChatRateLimit;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;

/**
 * RAG 对话服务默认实现
 * <p>
 * 核心流程：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 检索(MCP+KB) -> Prompt 组装 -> 流式输出
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ConversationMemoryService memoryService;
    private final StreamTaskManager taskManager;
    private final IntentGuidanceService guidanceService;
    private final StreamCallbackFactory callbackFactory;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final RetrievalEngine retrievalEngine;

    /**
     * RAG 流式问答主流程。
     * <p>
     * 该方法在真正进入实现时，外层可能已经经过限流切面排队，因此这里可以认为自己已经拿到了
     * 执行资格。方法内部按照“记忆补全 -> 问题改写 -> 意图识别 -> 歧义判断 -> 检索/直答 ->
     * 模型流式输出”的顺序推进，并在合适时将可取消句柄绑定到任务管理器，供前端停止生成时使用。
     */
    @Override
    @ChatRateLimit
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        // conversationId 为空时创建新会话，保证后续记忆、检索、回调都围绕同一个会话 ID 运行。
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        // 优先复用 trace 上下文中的 taskId；若当前链路未注入，则本地补一个新的任务 ID。
        String taskId = StrUtil.isBlank(RagTraceContext.getTaskId())
                ? IdUtil.getSnowflakeNextIdStr()
                : RagTraceContext.getTaskId();
        log.info("开始流式对话，会话ID：{}，任务ID：{}", actualConversationId, taskId);
        // deepThinking 允许为 null，这里统一折叠成布尔值，避免后续分支重复判空。
        boolean thinkingEnabled = Boolean.TRUE.equals(deepThinking);

        // 回调对象负责把模型增量输出、结束事件和异常统一写回 SSE，并串联记忆落库等后置逻辑。
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        try {
            String userId = UserContext.getUserId();
            // 先把当前用户问题追加进会话记忆，再加载完整历史，确保后续改写/Prompt 看到的是最新上下文。
            List<ChatMessage> history = memoryService.loadAndAppend(actualConversationId, userId, ChatMessage.user(question));

            // 将原始问题改写成更适合检索的表达，并在需要时拆分出多个子问题。
            RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, history);
            // 针对改写后的问题与子问题做意图解析，决定后续走系统直答、知识库检索还是 MCP 工具分支。
            List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);

            // 若问题存在明显歧义，优先让用户补充信息，避免在错误前提上继续检索和生成。
            GuidanceDecision guidanceDecision = guidanceService.detectAmbiguity(rewriteResult.rewrittenQuestion(), subIntents);
            if (guidanceDecision.isPrompt()) {
                callback.onContent(guidanceDecision.getPrompt());
                callback.onComplete();
                return;
            }

            // 所有子问题都只命中系统节点时，说明无需外部检索，直接走系统 Prompt 流式回答即可。
            boolean allSystemOnly = subIntents.stream()
                    .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
            if (allSystemOnly) {
                // 若节点提供了专用 Prompt 模板，则优先使用；否则回退到通用系统 Prompt。
                String customPrompt = subIntents.stream()
                        .flatMap(si -> si.nodeScores().stream())
                        .map(ns -> ns.getNode().getPromptTemplate())
                        .filter(StrUtil::isNotBlank)
                        .findFirst()
                        .orElse(null);
                // 返回的 handle 用于后续 stopTask 时中断模型流式生成。
                StreamCancellationHandle handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), history, customPrompt, callback);
                taskManager.bindHandle(taskId, handle);
                return;
            }

            // 非系统直答场景进入 RAG 检索，聚合知识库片段、MCP 工具结果等增强上下文。
            RetrievalContext ctx = retrievalEngine.retrieve(subIntents, DEFAULT_TOP_K);
            if (ctx.isEmpty()) {
                // 没有命中有效上下文时直接友好返回，避免模型在无依据情况下自由发挥。
                String emptyReply = "未检索到与问题相关的文档内容。";
                callback.onContent(emptyReply);
                callback.onComplete();
                return;
            }

            // 聚合所有意图用于 prompt 规划
            // 这里会把拆分后的多子问题意图合并，便于统一规划 Prompt 中的检索上下文和回答策略。
            IntentGroup mergedGroup = intentResolver.mergeIntentGroup(subIntents);

            // 组装最终 Prompt 并发起真正的流式模型调用。
            StreamCancellationHandle handle = streamLLMResponse(
                    rewriteResult,
                    ctx,
                    mergedGroup,
                    history,
                    thinkingEnabled,
                    callback
            );
            // 将句柄绑定到任务，供 stopTask 或异常清理时取消底层流式请求。
            taskManager.bindHandle(taskId, handle);
        } catch (Exception e) {
            log.warn("流式对话执行失败，会话ID：{}，任务ID：{}", actualConversationId, taskId, e);
            callback.onError(e);
        }
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }

    // ==================== LLM 响应 ====================

    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history.subList(0, history.size() - 1));
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }
}
