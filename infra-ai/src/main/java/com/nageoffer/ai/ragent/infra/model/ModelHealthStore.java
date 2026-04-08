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

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型健康状态存储器
 * 用于管理和跟踪各个 AI 模型的健康状况，实现断路器模式
  
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 * 维护三态状态机：
 * CLOSED 表示正常放行，OPEN 表示熔断冷却中，HALF_OPEN 表示只允许单个探测请求试运行。
 */@Component
@RequiredArgsConstructor
public class ModelHealthStore {

    private final AIModelProperties properties;

    private final Map<String, ModelHealth> healthById = new ConcurrentHashMap<>();

    public boolean isUnavailable(String id) {
        ModelHealth health = healthById.get(id);
        if (health == null) {
            return false;
        }
        // 该方法只做静态可用性判断，不负责推进 OPEN -> HALF_OPEN 的状态切换。
        if (health.state == State.OPEN && health.openUntil > System.currentTimeMillis()) {
            return true;
        }
        return health.state == State.HALF_OPEN && health.halfOpenInFlight;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean allowCall(String id) {
        if (id == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        AtomicBoolean allowed = new AtomicBoolean(false);
        // 所有状态转换都收敛在 compute 中，保证并发场景下 HALF_OPEN 只放过一个探测请求。
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            if (v.state == State.OPEN) {
                if (v.openUntil > now) {
                    return v;
                }
                // 冷却期结束后进入 HALF_OPEN，并把这次调用标记为唯一探测请求。
                v.state = State.HALF_OPEN;
                v.halfOpenInFlight = true;
                allowed.set(true);
                return v;
            }
            if (v.state == State.HALF_OPEN) {
                if (v.halfOpenInFlight) {
                    return v;
                }
                // HALF_OPEN 下只有前一个探测请求已结束，才允许下一次试探。
                v.halfOpenInFlight = true;
                allowed.set(true);
                return v;
            }
            allowed.set(true);
            return v;
        });
        return allowed.get();
    }

    public void markSuccess(String id) {
        if (id == null) {
            return;
        }
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                return new ModelHealth();
            }
            v.state = State.CLOSED;
            v.consecutiveFailures = 0;
            v.openUntil = 0L;
            v.halfOpenInFlight = false;
            return v;
        });
    }

    public void markFailure(String id) {
        if (id == null) {
            return;
        }
        long now = System.currentTimeMillis();
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            if (v.state == State.HALF_OPEN) {
                // 探测请求失败时立即重新打开熔断，而不是继续累计失败次数。
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
                v.halfOpenInFlight = false;
                return v;
            }
            v.consecutiveFailures++;
            if (v.consecutiveFailures >= properties.getSelection().getFailureThreshold()) {
                // CLOSED 状态下按阈值累计失败，达到阈值后进入 OPEN 冷却窗口。
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
            }
            return v;
        });
    }

    private static class ModelHealth {
        private int consecutiveFailures;
        private long openUntil;
        private boolean halfOpenInFlight;
        private State state;

        private ModelHealth() {
            this.consecutiveFailures = 0;
            this.openUntil = 0L;
            this.halfOpenInFlight = false;
            this.state = State.CLOSED;
        }
    }

    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}
