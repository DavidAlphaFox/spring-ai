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

package org.springframework.ai.rag.retrieval.search;

import java.util.List;
import java.util.function.Function;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

/**
 * Component responsible for retrieving {@link Document}s from an underlying data source,
 * such as a search engine, a vector store, a database, or a knowledge graph.
 *
 * <h2>RAG 阶段：检索（Retrieval）</h2>
 * <p>
 * 这是 RAG 流水线最核心的一环：把"查询"映射成"<b>相关文档列表</b>"。底层数据源完全可插拔：
 * 向量库（PgVector / Milvus / Weaviate / Redis 等）、传统搜索引擎、关系数据库、知识图谱都可以。
 * Spring AI 提供了 {@code VectorStoreDocumentRetriever} 作为默认基于向量库的实现，
 * 用户也可以自定义实现来对接 BM25 全文检索或混合检索。
 *
 * <h2>线程模型</h2>
 * <p>
 * 当 {@code QueryExpander} 把查询扩展为多个时，{@code RetrievalAugmentationAdvisor}
 * 会<b>并行</b>调用 {@link #retrieve(Query)}（基于内置 TaskExecutor），
 * 因此实现需保证线程安全（典型实现一般是无状态的）。
 *
 * @author Christian Tzolov
 * @author Thomas Vitale
 * @since 1.0.0
 */
public interface DocumentRetriever extends Function<Query, List<Document>> {

	/**
	 * Retrieves relevant documents from an underlying data source based on the given
	 * query.
	 * @param query 查询条件（含文本、历史、上下文等）
	 * @return 相关文档列表（顺序通常代表相关性，由具体实现决定）
	 */
	List<Document> retrieve(Query query);

	default List<Document> apply(Query query) {
		return retrieve(query);
	}

}
