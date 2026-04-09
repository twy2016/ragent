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

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

/**
 * vLLM Chat 客户端
 * <p>
 * vLLM 默认兼容 OpenAI Chat Completions；针对 Qwen3 系列额外补充
 * chat_template_kwargs.enable_thinking，以对齐项目中的深度思考开关语义。
 */
@Service
public class VllmChatClient extends AbstractOpenAIStyleChatClient {

    public VllmChatClient(OkHttpClient httpClient, Executor modelStreamExecutor) {
        super(httpClient, modelStreamExecutor);
    }

    @Override
    public String provider() {
        return ModelProvider.VLLM.getId();
    }

    @Override
    protected boolean requiresApiKey() {
        return false;
    }

    @Override
    protected void customizeRequestBody(JsonObject body, ChatRequest request) {
        JsonObject chatTemplateKwargs = new JsonObject();
        chatTemplateKwargs.addProperty("enable_thinking", Boolean.TRUE.equals(request.getThinking()));
        body.add("chat_template_kwargs", chatTemplateKwargs);
    }

    @Override
    protected void customizeRequestHeaders(Request.Builder builder, AIModelProperties.ProviderConfig providerConfig) {
        if (providerConfig != null
                && providerConfig.getApiKey() != null
                && !providerConfig.getApiKey().isBlank()) {
            builder.addHeader("Authorization", "Bearer " + providerConfig.getApiKey());
        }
    }

    @Override
    @RagTraceNode(name = "vllm-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        return doChat(request, target);
    }

    @Override
    @RagTraceNode(name = "vllm-stream-chat", type = "LLM_PROVIDER")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        return doStreamChat(request, callback, target);
    }
}
