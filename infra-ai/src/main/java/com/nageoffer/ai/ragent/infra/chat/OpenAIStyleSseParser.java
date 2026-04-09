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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.NoArgsConstructor;

/**
 * OpenAI 协议风格 SSE 解析器
 * 支持从 delta/message 中提取 content，以及可选的 reasoning/reasoning_content
 * <p>
 * 主要服务于兼容 OpenAI 风格流式返回格式的聊天客户端实现。
 * 同时兼容两类常见载荷结构：增量流片段 `choices[].delta` 与一次性消息 `choices[].message`。
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class OpenAIStyleSseParser {

    private static final String DATA_PREFIX = "data:";
    private static final String DONE_MARKER = "[DONE]";

    static ParsedEvent parseLine(String line, Gson gson, boolean reasoningEnabled) {
        if (line == null || line.isBlank()) {
            return ParsedEvent.empty();
        }

        String payload = line.trim();
        if (payload.startsWith(DATA_PREFIX)) {
            payload = payload.substring(DATA_PREFIX.length()).trim();
        }
        if (DONE_MARKER.equalsIgnoreCase(payload)) {
            return ParsedEvent.done();
        }

        JsonObject obj = gson.fromJson(payload, JsonObject.class);
        JsonArray choices = obj.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return ParsedEvent.empty();
        }

        JsonObject choice0 = choices.get(0).getAsJsonObject();
        String content = extractText(choice0, "content");
        String reasoning = reasoningEnabled ? extractReasoning(choice0) : null;
        boolean completed = hasFinishReason(choice0);

        return new ParsedEvent(content, reasoning, completed);
    }

    private static String extractReasoning(JsonObject choice) {
        String reasoning = extractText(choice, "reasoning");
        if (reasoning != null && !reasoning.isEmpty()) {
            return reasoning;
        }
        return extractText(choice, "reasoning_content");
    }

    private static boolean hasFinishReason(JsonObject choice) {
        if (choice == null || !choice.has("finish_reason")) {
            return false;
        }
        JsonElement finishReason = choice.get("finish_reason");
        return finishReason != null && !finishReason.isJsonNull();
    }

    private static String extractText(JsonObject choice, String fieldName) {
        if (choice == null) {
            return null;
        }
        // 优先读取 delta，兼容标准 SSE 增量场景。
        if (choice.has("delta") && choice.get("delta").isJsonObject()) {
            JsonObject delta = choice.getAsJsonObject("delta");
            if (delta.has(fieldName)) {
                JsonElement value = delta.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        // 部分提供商会在非严格流式或最后一个分片里直接返回完整 message，这里做兜底兼容。
        if (choice.has("message") && choice.get("message").isJsonObject()) {
            JsonObject message = choice.getAsJsonObject("message");
            if (message.has(fieldName)) {
                JsonElement value = message.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        return null;
    }

    record ParsedEvent(String content, String reasoning, boolean completed) {

        static ParsedEvent empty() {
            return new ParsedEvent(null, null, false);
        }

        static ParsedEvent done() {
            return new ParsedEvent(null, null, true);
        }

        boolean hasContent() {
            return content != null && !content.isEmpty();
        }

        boolean hasReasoning() {
            return reasoning != null && !reasoning.isEmpty();
        }
    }
}
