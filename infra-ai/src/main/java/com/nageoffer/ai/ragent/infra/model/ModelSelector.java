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

package com.nageoffer.ai.ragent.infra.model;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 模型选择器
 * 负责根据配置和当前需求（如普通对话、深度思考、Embedding等）选择合适的模型候选列表
  
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 * 候选列表的生成顺序分为三步：先过滤禁用项与能力不匹配项，再按 priority/id 排序，
 * 最后把当前场景的首选模型提升到队头，并在绑定 provider 配置时跳过已熔断模型。
 */@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSelector {

    private final AIModelProperties properties;
    private final ModelHealthStore healthStore;

    public List<ModelTarget> selectChatCandidates(Boolean deepThinking) {
        AIModelProperties.ModelGroup group = properties.getChat();
        if (group == null) {
            return List.of();
        }

        String firstChoiceModelId = resolveFirstChoiceModel(group, deepThinking);
        return selectCandidates(group, firstChoiceModelId, deepThinking);
    }

    public List<ModelTarget> selectEmbeddingCandidates() {
        return selectCandidates(properties.getEmbedding());
    }

    public List<ModelTarget> selectRerankCandidates() {
        return selectCandidates(properties.getRerank());
    }

    public ModelTarget selectDefaultEmbedding() {
        List<ModelTarget> targets = selectEmbeddingCandidates();
        return targets.isEmpty() ? null : targets.get(0);
    }

    /**
     * 根据模式解析首选模型
     * - 深度思考模式：优先使用 deep-thinking-model
     * - 普通模式：使用 default-model
     */
    private String resolveFirstChoiceModel(AIModelProperties.ModelGroup group, Boolean deepThinking) {
        if (Boolean.TRUE.equals(deepThinking)) {
            String deepModel = group.getDeepThinkingModel();
            if (StrUtil.isNotBlank(deepModel)) {
                return deepModel;
            }
        }
        return group.getDefaultModel();
    }

    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group) {
        if (group == null) {
            return List.of();
        }
        return selectCandidates(group, group.getDefaultModel(), null);
    }

    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group, String firstChoiceModelId, Boolean deepThinking) {
        if (group == null || group.getCandidates() == null) {
            return List.of();
        }

        List<AIModelProperties.ModelCandidate> orderedCandidates =
                prepareOrderedCandidates(group.getCandidates(), firstChoiceModelId, deepThinking);

        return buildAvailableTargets(orderedCandidates);
    }

    /**
     * 准备排序后的候选模型列表
     */
    private List<AIModelProperties.ModelCandidate> prepareOrderedCandidates(
            List<AIModelProperties.ModelCandidate> candidates,
            String firstChoiceModelId,
            Boolean deepThinking) {
        List<AIModelProperties.ModelCandidate> enabled = candidates.stream()
                // 深度思考模式下只保留显式声明 supportsThinking=true 的候选，避免路由到不支持 thinking 的模型。
                .filter(c -> c != null && !Boolean.FALSE.equals(c.getEnabled()))
                .filter(c -> !Boolean.TRUE.equals(deepThinking) || Boolean.TRUE.equals(c.getSupportsThinking()))
                .sorted(Comparator
                        .comparing(AIModelProperties.ModelCandidate::getPriority,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(AIModelProperties.ModelCandidate::getId,
                                Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toCollection(ArrayList::new));

        if (Boolean.TRUE.equals(deepThinking) && enabled.isEmpty()) {
            log.warn("深度思考模式没有可用候选模型");
            return enabled;
        }

        promoteFirstChoiceModel(enabled, firstChoiceModelId);

        return enabled;
    }

    private void promoteFirstChoiceModel(
            List<AIModelProperties.ModelCandidate> candidates,
            String firstChoiceModelId) {

        if (StrUtil.isBlank(firstChoiceModelId)) {
            return;
        }

        AIModelProperties.ModelCandidate firstChoice = findCandidate(candidates, firstChoiceModelId);
        if (firstChoice == null) {
            log.warn("首选模型在候选列表中未找到: modelId={}", firstChoiceModelId);
            return;
        }
        candidates.remove(firstChoice);
        candidates.add(0, firstChoice);
    }

    private List<ModelTarget> buildAvailableTargets(
            List<AIModelProperties.ModelCandidate> candidates) {

        Map<String, AIModelProperties.ProviderConfig> providers = properties.getProviders();

        return candidates.stream()
                // 这一阶段把候选模型补齐 provider 配置，并顺带过滤掉已进入 OPEN/HALF_OPEN 不可调用状态的模型。
                .map(candidate -> buildModelTarget(candidate, providers))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private ModelTarget buildModelTarget(AIModelProperties.ModelCandidate candidate, Map<String, AIModelProperties.ProviderConfig> providers) {
        String modelId = resolveId(candidate);

        // 检查熔断状态
        if (healthStore.isUnavailable(modelId)) {
            return null;
        }

        // 验证 provider 配置
        AIModelProperties.ProviderConfig provider = providers.get(candidate.getProvider());
        if (provider == null && !ModelProvider.NOOP.matches(candidate.getProvider())) {
            log.warn("Provider配置缺失: provider={}, modelId={}",
                    candidate.getProvider(), modelId);
            return null;
        }

        return new ModelTarget(modelId, candidate, provider);
    }

    private AIModelProperties.ModelCandidate findCandidate(
            List<AIModelProperties.ModelCandidate> candidates,
            String id) {

        return candidates.stream()
                .filter(c -> id.equals(c.getId()))
                .findFirst()
                .orElse(null);
    }

    private String resolveId(AIModelProperties.ModelCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        if (StrUtil.isNotBlank(candidate.getId())) {
            return candidate.getId();
        }
        // 兼容旧配置未显式填写 id 的情况，确保健康检查与日志仍然有稳定主键。
        return String.format("%s::%s",
                Objects.toString(candidate.getProvider(), "unknown"),
                Objects.toString(candidate.getModel(), "unknown"));
    }
}
