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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;

import java.util.Date;

/**
 * RAG Trace 记录服务
 * <p>
 * 面向切面层提供 run/node 生命周期持久化能力，不承载额外业务判断。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */public interface RagTraceRecordService {

    /**
     * 记录一条 trace 根运行开始事件。
     */
    void startRun(RagTraceRunDO run);

    /**
     * 记录一条 trace 根运行结束事件。
     */
    void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs);

    /**
     * 记录一个 trace 节点开始事件。
     */
    void startNode(RagTraceNodeDO node);

    /**
     * 记录一个 trace 节点结束事件。
     */
    void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs);
}
