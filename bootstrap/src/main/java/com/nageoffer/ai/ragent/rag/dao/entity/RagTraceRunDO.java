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

package com.nageoffer.ai.ragent.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * RAG Trace 运行记录
 * <p>
 * 表示一整条 trace 根链路的生命周期信息，通常由 @RagTraceRoot 或流式入口切面创建。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_rag_trace_run")
public class RagTraceRunDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 全局链路ID
     */
    private String traceId;

    /**
     * 链路名称
     */
    private String traceName;

    /**
     * 触发入口方法
     */
    private String entryMethod;

    private String conversationId;

    private String taskId;

    private String userId;

    /**
     * RUNNING / SUCCESS / ERROR
     */
    private String status;

    private String errorMessage;

    private Date startTime;

    private Date endTime;

    private Long durationMs;

    /**
     * 预留扩展字段（JSON字符串）
     */
    private String extraData;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
