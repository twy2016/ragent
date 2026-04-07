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
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.QueryTermMappingVO;

/**
 * 查询术语映射规则管理服务。
 * <p>
 * 主要面向后台管理端，用于维护用户说法和标准检索术语之间的映射关系。
 
 * <p>
 * 用于承载当前模块中的具体业务或基础设施能力。
 */public interface QueryTermMappingAdminService {

    /**
     * 创建映射规则
     */
    String create(QueryTermMappingCreateRequest requestParam);

    /**
     * 更新映射规则
     */
    void update(String id, QueryTermMappingUpdateRequest requestParam);

    /**
     * 删除映射规则
     */
    void delete(String id);

    /**
     * 查询映射规则详情
     */
    QueryTermMappingVO queryById(String id);

    /**
     * 分页查询映射规则
     */
    IPage<QueryTermMappingVO> pageQuery(QueryTermMappingPageRequest requestParam);
}
