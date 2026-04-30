/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.rag.postretrieval.document;

import java.util.List;
import java.util.function.BiFunction;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

/**
 * A component for post-processing retrieved documents based on a query, addressing
 * challenges such as "lost-in-the-middle", context length restrictions from the model,
 * and the need to reduce noise and redundancy in the retrieved information.
 * <p>
 * For example, it could rank documents based on their relevance to the query, remove
 * irrelevant or redundant documents, or compress the content of each document to reduce
 * noise and redundancy.
 *
 * <h2>RAG 阶段：检索后处理（Post-retrieval）</h2>
 * <p>
 * 此阶段在文档已合并、但尚未拼入 Prompt 之前介入。它要解决的痛点：
 * <ul>
 *   <li><b>"中段失忆"（Lost-in-the-Middle）</b>：LLM 对长上下文中部信息的利用率较差，
 *       因此常需要把最相关的文档<i>重排</i>到首尾位置；</li>
 *   <li><b>上下文长度受限</b>：模型 token 窗口有限，需要裁剪 / 压缩文档；</li>
 *   <li><b>降噪与去冗</b>：剔除与查询无关的文档、去除内容上的冗余。</li>
 * </ul>
 * 典型实现：基于 Reranker 模型的精排、基于摘要模型的内容压缩、基于规则的过滤等。
 * {@code RetrievalAugmentationAdvisor} 支持注册一个 List，依次执行多个后处理器。
 *
 * @author Thomas Vitale
 * @since 1.0.0
 */
public interface DocumentPostProcessor extends BiFunction<Query, List<Document>, List<Document>> {

	/**
	 * 对一组已检索到的文档做后处理。
	 * @param query 原始查询，可用于打分 / 重排
	 * @param documents 待处理的文档列表
	 * @return 处理后的文档列表（可能被重排、过滤、压缩）
	 */
	List<Document> process(Query query, List<Document> documents);

	default List<Document> apply(Query query, List<Document> documents) {
		return process(query, documents);
	}

}
