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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
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
import java.util.Set;

/**
 * vLLM Rerank 客户端
 * <p>
 * 复用 vLLM /rerank 接口，解析 results[].index / relevance_score。
 */
@Service
@Slf4j
    @RequiredArgsConstructor
public class VllmRerankClient implements RerankClient {

    private static final String FIELD_RELEVANCE_SCORE = "relevance_score";
    private static final String FIELD_SCORE = "score";

    private final OkHttpClient httpClient;

    @Override
    public String provider() {
        return ModelProvider.VLLM.getId();
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
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", HttpResponseHelper.requireModel(target, provider()));
        reqBody.addProperty("query", query == null ? "" : query);

        JsonArray documents = new JsonArray();
        for (RetrievedChunk each : candidates) {
            documents.add(each.getText() == null ? "" : each.getText());
        }
        reqBody.add("documents", documents);
        reqBody.addProperty("top_n", topN);

        Request.Builder builder = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON));
        if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
            builder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }

        JsonObject respJson;
        try (Response response = httpClient.newCall(builder.build()).execute()) {
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
            throw new ModelClientException(provider() + " rerank 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        JsonArray results = respJson == null ? null : respJson.getAsJsonArray("results");
        if (results == null || results.isEmpty()) {
            throw new ModelClientException(provider() + " rerank 响应缺少 results", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        List<RetrievedChunk> reranked = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();

        for (JsonElement element : results) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            if (!item.has("index") || item.get("index").isJsonNull()) {
                continue;
            }

            int index = item.get("index").getAsInt();
            if (index < 0 || index >= candidates.size()) {
                continue;
            }

            RetrievedChunk source = candidates.get(index);
            if (source == null || !addedIds.add(source.getId())) {
                continue;
            }

            Float score = null;
            score = extractScore(item);

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

    private Float extractScore(JsonObject item) {
        if (item == null) {
            return null;
        }
        if (item.has(FIELD_RELEVANCE_SCORE) && !item.get(FIELD_RELEVANCE_SCORE).isJsonNull()) {
            return item.get(FIELD_RELEVANCE_SCORE).getAsFloat();
        }
        if (item.has(FIELD_SCORE) && !item.get(FIELD_SCORE).isJsonNull()) {
            return item.get(FIELD_SCORE).getAsFloat();
        }
        return null;
    }
}
