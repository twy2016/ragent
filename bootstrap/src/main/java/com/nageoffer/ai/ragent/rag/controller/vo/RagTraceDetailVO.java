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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * RAG Trace 详情
 * <p>
 * 同时承载 trace 根记录和节点明细，便于前端一次性渲染完整链路。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */@Data
@Builder
public class RagTraceDetailVO {

    private RagTraceRunVO run;

    private List<RagTraceNodeVO> nodes;
}
