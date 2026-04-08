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

import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式首包探测回调
 * <p>
 * 在路由层确认“当前候选模型真的拿到首包”之前，所有事件先缓存在本地；
 * 这样一旦首包超时或请求失败并切换到下一个模型，就不会把前一个候选的半截输出透传给下游。
 */
public final class ProbeBufferingCallback implements StreamCallback {

    private final StreamCallback downstream;
    private final FirstPacketAwaiter awaiter;
    private final Object lock = new Object();
    private final List<BufferedEvent> bufferedEvents = new ArrayList<>();
    private volatile boolean committed;

    ProbeBufferingCallback(StreamCallback downstream, FirstPacketAwaiter awaiter) {
        this.downstream = downstream;
        this.awaiter = awaiter;
        this.committed = false;
    }

    @Override
    public void onContent(String content) {
        awaiter.markContent();
        bufferOrDispatch(BufferedEvent.content(content));
    }

    @Override
    public void onThinking(String content) {
        awaiter.markContent();
        bufferOrDispatch(BufferedEvent.thinking(content));
    }

    @Override
    public void onComplete() {
        awaiter.markComplete();
        bufferOrDispatch(BufferedEvent.complete());
    }

    @Override
    public void onError(Throwable t) {
        awaiter.markError(t);
        bufferOrDispatch(BufferedEvent.error(t));
    }

    /**
     * 首包探测成功后提交
     */
    void commit() {
        List<BufferedEvent> snapshot;
        synchronized (lock) {
            if (committed) {
                return;
            }
            committed = true;
            if (bufferedEvents.isEmpty()) {
                return;
            }
            // 先在锁内拍快照，再在锁外分发，避免下游回调阻塞网络读取线程。
            snapshot = new ArrayList<>(bufferedEvents);
            bufferedEvents.clear();
        }
        for (BufferedEvent event : snapshot) {
            dispatch(event);
        }
    }

    private void bufferOrDispatch(BufferedEvent event) {
        boolean dispatchNow;
        // 通过同一把锁维护“提交前缓存、提交后直发”的切换点，保证事件顺序不乱。
        synchronized (lock) {
            dispatchNow = committed;
            if (!dispatchNow) {
                bufferedEvents.add(event);
            }
        }
        if (dispatchNow) {
            dispatch(event);
        }
    }

    private void dispatch(BufferedEvent event) {
        switch (event.type()) {
            case CONTENT -> downstream.onContent(event.content());
            case THINKING -> downstream.onThinking(event.content());
            case COMPLETE -> downstream.onComplete();
            // 探测阶段若上游只报告了空错误对象，这里补一个统一异常，避免下游收到 null。
            case ERROR -> downstream.onError(event.error() != null
                    ? event.error()
                    : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR));
        }
    }

    private record BufferedEvent(EventType type, String content, Throwable error) {

        private static BufferedEvent content(String content) {
            return new BufferedEvent(EventType.CONTENT, content, null);
        }

        private static BufferedEvent thinking(String content) {
            return new BufferedEvent(EventType.THINKING, content, null);
        }

        private static BufferedEvent complete() {
            return new BufferedEvent(EventType.COMPLETE, null, null);
        }

        private static BufferedEvent error(Throwable error) {
            return new BufferedEvent(EventType.ERROR, null, error);
        }
    }

    private enum EventType {
        CONTENT,
        THINKING,
        COMPLETE,
        ERROR
    }
}
