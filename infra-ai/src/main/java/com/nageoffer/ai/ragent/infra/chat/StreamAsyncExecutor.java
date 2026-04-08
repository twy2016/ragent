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

import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import lombok.NoArgsConstructor;
import okhttp3.Call;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 流式任务异步执行器
 * 统一处理线程池提交、拒绝兜底和取消句柄构建逻辑
 * <p>
 * 主要用于把底层流式网络读取任务切到独立线程池执行，并统一处理线程池繁忙时的降级行为。
 * 调用方拿到的取消句柄与底层 OkHttp Call 绑定，确保上层取消能及时中断网络读取。
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class StreamAsyncExecutor {

    private static final String STREAM_BUSY_MESSAGE = "流式线程池繁忙";

    static StreamCancellationHandle submit(Executor executor,
                                           Call call,
                                           StreamCallback callback,
                                           Consumer<AtomicBoolean> streamTask) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        try {
            CompletableFuture.runAsync(() -> streamTask.accept(cancelled), executor);
        } catch (RejectedExecutionException ex) {
            // 提交阶段失败时还没有真正开始读取流，这里直接回调错误并返回空取消句柄即可。
            call.cancel();
            callback.onError(new ModelClientException(STREAM_BUSY_MESSAGE, ModelClientErrorType.SERVER_ERROR, null, ex));
            return StreamCancellationHandles.noop();
        }
        return StreamCancellationHandles.fromOkHttp(call, cancelled);
    }
}
