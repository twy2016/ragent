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

package com.nageoffer.ai.ragent.infra.rerank;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 Ollama Chat Completions 的重排客户端
 * <p>
 * Ollama 当前没有独立的 rerank HTTP 端点，这里通过 OpenAI 兼容的 chat/completions 接口，
 * 让本地模型返回结构化 JSON 排序结果，再映射回 RetrievedChunk 列表。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OllamaRerankClient implements RerankClient {

    private static final int MAX_DOCUMENT_CHARS = 800;
    private static final int MAX_OUTPUT_TOKENS = 512;
    private static final String RESPONSE_FIELD_RESULTS = "results";
    private static final String RESPONSE_FIELD_INDEX = "index";
    private static final String RESPONSE_FIELD_SCORE = "score";
    private static final Gson GSON = new Gson();

    private final OkHttpClient httpClient;

    @Override
    public String provider() {
        return ModelProvider.OLLAMA.getId();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (CollUtil.isEmpty(candidates)) {
            return List.of();
        }

        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk chunk : candidates) {
            if (chunk != null && seen.add(chunk.getId())) {
                dedup.add(chunk);
            }
        }

        if (topN <= 0 || dedup.size() <= topN) {
            return dedup;
        }

        return doRerank(query, dedup, topN, target);
    }

    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        AIModelProperties.ProviderConfig providerConfig = HttpResponseHelper.requireProvider(target, provider());
        String model = HttpResponseHelper.requireModel(target, provider());
        Request request = buildRequest(query, candidates, topN, target, providerConfig, model);

        JsonObject respJson;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} rerank 请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " rerank 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(
                    provider() + " rerank 请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR,
                    null,
                    e
            );
        }

        String content = extractMessageContent(respJson);
        return mapRerankedChunks(content, candidates, topN);
    }

    private Request buildRequest(String query,
                                 List<RetrievedChunk> candidates,
                                 int topN,
                                 ModelTarget target,
                                 AIModelProperties.ProviderConfig providerConfig,
                                 String model) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("temperature", 0);
        requestBody.addProperty("max_tokens", MAX_OUTPUT_TOKENS);
        requestBody.addProperty("stream", false);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        requestBody.add("response_format", responseFormat);
        requestBody.add("messages", buildMessages(query, candidates, topN));

        Request.Builder builder = new Request.Builder()
                .url(resolveRequestUrl(providerConfig, target))
                .post(RequestBody.create(requestBody.toString(), HttpMediaTypes.JSON));

        if (StrUtil.isNotBlank(providerConfig.getApiKey())) {
            builder.addHeader("Authorization", "Bearer " + providerConfig.getApiKey());
        }
        return builder.build();
    }

    private JsonArray buildMessages(String query, List<RetrievedChunk> candidates, int topN) {
        JsonArray messages = new JsonArray();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty(
                "content",
                """
                你是检索结果重排器。
                你的任务是根据用户查询，对候选文档按相关性从高到低重新排序。
                你必须只返回 JSON，不要输出解释、不要输出 Markdown。
                JSON 结构必须是：
                {"results":[{"index":0,"score":0.98}]}
                约束：
                1. index 必须引用输入中的候选编号；
                2. score 取值范围为 0 到 1，越大表示越相关；
                3. results 必须按相关性降序排列；
                4. 最多返回 top_n 条结果；
                5. 不要编造不存在的 index。
                """
        );
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", buildUserPrompt(query, candidates, topN));
        messages.add(user);

        return messages;
    }

    private String buildUserPrompt(String query, List<RetrievedChunk> candidates, int topN) {
        StringBuilder builder = new StringBuilder();
        builder.append("query:\n")
                .append(StrUtil.blankToDefault(query, ""))
                .append("\n\n")
                .append("top_n:\n")
                .append(topN)
                .append("\n\n")
                .append("documents:\n");

        for (int i = 0; i < candidates.size(); i++) {
            RetrievedChunk chunk = candidates.get(i);
            builder.append('[')
                    .append(i)
                    .append("] ")
                    .append(compactText(chunk.getText()))
                    .append('\n');
        }
        return builder.toString();
    }

    private String compactText(String text) {
        String compact = StrUtil.blankToDefault(text, "")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (compact.length() <= MAX_DOCUMENT_CHARS) {
            return compact;
        }
        return compact.substring(0, MAX_DOCUMENT_CHARS);
    }

    private String resolveRequestUrl(AIModelProperties.ProviderConfig providerConfig, ModelTarget target) {
        if (target != null && target.candidate() != null && StrUtil.isNotBlank(target.candidate().getUrl())) {
            return target.candidate().getUrl();
        }
        if (providerConfig == null || StrUtil.isBlank(providerConfig.getUrl())) {
            throw new IllegalStateException("Ollama provider baseUrl is missing");
        }
        Map<String, String> endpoints = providerConfig.getEndpoints();
        String path = endpoints == null ? null : endpoints.get("rerank");
        if (StrUtil.isBlank(path)) {
            path = endpoints == null ? null : endpoints.get("chat");
        }
        if (StrUtil.isBlank(path)) {
            throw new IllegalStateException("Ollama rerank/chat endpoint is missing");
        }
        return joinUrl(providerConfig.getUrl(), path);
    }

    private String joinUrl(String baseUrl, String path) {
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl + path.substring(1);
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }

    private String extractMessageContent(JsonObject response) {
        if (response == null || !response.has("choices")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 choices", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        JsonArray choices = response.getAsJsonArray("choices");
        if (CollUtil.isEmpty(choices)) {
            throw new ModelClientException(provider() + " rerank choices 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        JsonObject choice0 = choices.get(0).getAsJsonObject();
        if (choice0 == null || !choice0.has("message")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        JsonObject message = choice0.getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException(provider() + " rerank 响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        return message.get("content").getAsString();
    }

    private List<RetrievedChunk> mapRerankedChunks(String rawContent, List<RetrievedChunk> candidates, int topN) {
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(rawContent);
        JsonObject parsed;
        try {
            parsed = GSON.fromJson(cleaned, JsonObject.class);
        } catch (Exception ex) {
            throw new ModelClientException(provider() + " rerank 响应不是合法 JSON", ModelClientErrorType.INVALID_RESPONSE, null, ex);
        }

        if (parsed == null || !parsed.has(RESPONSE_FIELD_RESULTS) || !parsed.get(RESPONSE_FIELD_RESULTS).isJsonArray()) {
            throw new ModelClientException(provider() + " rerank 响应缺少 results", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        JsonArray results = parsed.getAsJsonArray(RESPONSE_FIELD_RESULTS);
        List<RetrievedChunk> reranked = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();

        for (JsonElement element : results) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            if (!item.has(RESPONSE_FIELD_INDEX) || item.get(RESPONSE_FIELD_INDEX).isJsonNull()) {
                continue;
            }

            int index = item.get(RESPONSE_FIELD_INDEX).getAsInt();
            if (index < 0 || index >= candidates.size()) {
                continue;
            }

            RetrievedChunk source = candidates.get(index);
            if (source == null || !addedIds.add(source.getId())) {
                continue;
            }

            Float score = null;
            if (item.has(RESPONSE_FIELD_SCORE) && !item.get(RESPONSE_FIELD_SCORE).isJsonNull()) {
                score = item.get(RESPONSE_FIELD_SCORE).getAsFloat();
            }

            reranked.add(score != null
                    ? new RetrievedChunk(source.getId(), source.getText(), score)
                    : source);

            if (reranked.size() >= topN) {
                break;
            }
        }

        if (reranked.isEmpty()) {
            throw new ModelClientException(provider() + " rerank 响应没有有效排序结果", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        if (reranked.size() < topN) {
            for (RetrievedChunk candidate : candidates) {
                if (candidate != null && addedIds.add(candidate.getId())) {
                    reranked.add(candidate);
                }
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }

        return reranked;
    }
}
