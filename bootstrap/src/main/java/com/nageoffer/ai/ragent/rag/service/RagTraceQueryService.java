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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.rag.controller.request.RagTraceRunPageRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceDetailVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceNodeVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceRunVO;

import java.util.List;

/**
 * RAG Trace 查询服务
 * <p>
 * 提供 trace 根记录和节点详情的查询能力，供观测页面或排障接口使用。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */public interface RagTraceQueryService {

    /**
     * 分页查询 trace 根记录。
     */
    IPage<RagTraceRunVO> pageRuns(RagTraceRunPageRequest request);

    /**
     * 查询单条 trace 的根记录与节点详情。
     */
    RagTraceDetailVO detail(String traceId);

    /**
     * 查询指定 trace 下的全部节点记录。
     */
    List<RagTraceNodeVO> listNodes(String traceId);
}
