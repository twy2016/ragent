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

package com.nageoffer.ai.ragent.infra.embedding;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * vLLM Embedding 客户端
 * <p>
 * 复用 OpenAI Embeddings 协议，兼容 /v1/embeddings 返回的 data[].embedding 结构。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VllmEmbeddingClient implements EmbeddingClient {

    private static final int MAX_BATCH = 32;

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    @Override
    public String provider() {
        return ModelProvider.VLLM.getId();
    }

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        return embedBatch(List.of(text), target).get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        if (CollUtil.isEmpty(texts)) {
            return Collections.emptyList();
        }

        List<List<Float>> results = new ArrayList<>(Collections.nCopies(texts.size(), null));
        for (int i = 0, n = texts.size(); i < n; i += MAX_BATCH) {
            int end = Math.min(i + MAX_BATCH, n);
            List<String> slice = texts.subList(i, end);
            List<List<Float>> part = doEmbedOnce(slice, target);
            for (int k = 0; k < part.size(); k++) {
                results.set(i + k, part.get(k));
            }
        }

        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == null) {
                throw new ModelClientException("Embedding 结果缺失，index=" + i, ModelClientErrorType.INVALID_RESPONSE, null);
            }
        }
        return results;
    }

    private List<List<Float>> doEmbedOnce(List<String> texts, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        Integer expectedDimension = target != null && target.candidate() != null
                ? target.candidate().getDimension()
                : null;

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", HttpResponseHelper.requireModel(target, provider()));
        req.put("input", texts);
        req.put("encoding_format", "float");

        Request.Builder builder = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.EMBEDDING))
                .post(RequestBody.create(gson.toJson(req), HttpMediaTypes.JSON));
        if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
            builder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }

        JsonObject root;
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} embedding 请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " embedding 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            root = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(provider() + " embedding 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        if (root != null && root.has("error")) {
            JsonObject error = root.getAsJsonObject("error");
            String message = error != null && error.has("message") && !error.get("message").isJsonNull()
                    ? error.get("message").getAsString()
                    : "unknown";
            throw new ModelClientException(provider() + " embedding 响应错误: " + message, ModelClientErrorType.PROVIDER_ERROR, null);
        }

        JsonArray data = root == null ? null : root.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            throw new ModelClientException(provider() + " embedding 响应缺少 data", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        if (data.size() != texts.size()) {
            throw new ModelClientException(
                    provider() + " embedding 数量不匹配: 期望 " + texts.size() + "，实际 " + data.size(),
                    ModelClientErrorType.INVALID_RESPONSE,
                    null
            );
        }

        List<List<Float>> vectors = new ArrayList<>(data.size());
        for (JsonElement item : data) {
            JsonObject obj = item.getAsJsonObject();
            JsonArray embedding = obj == null ? null : obj.getAsJsonArray("embedding");
            if (embedding == null || embedding.isEmpty()) {
                throw new ModelClientException(provider() + " embedding 响应缺少 embedding", ModelClientErrorType.INVALID_RESPONSE, null);
            }
            List<Float> vector = new ArrayList<>(embedding.size());
            for (JsonElement num : embedding) {
                vector.add(num.getAsFloat());
            }
            validateDimension(vector, expectedDimension);
            vectors.add(vector);
        }
        return vectors;
    }

    private void validateDimension(List<Float> vector, Integer expectedDimension) {
        if (expectedDimension == null || vector == null) {
            return;
        }
        if (vector.size() != expectedDimension) {
            throw new ModelClientException(
                    provider() + " embedding 维度不匹配: 期望 " + expectedDimension + "，实际 " + vector.size(),
                    ModelClientErrorType.INVALID_RESPONSE,
                    null
            );
        }
    }
}
