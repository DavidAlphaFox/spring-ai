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

package org.springframework.ai.rag.retrieval.join;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

/**
 * A component for combining documents retrieved based on multiple queries and from
 * multiple data sources into a single collection of documents. As part of the joining
 * process, it can also handle duplicate documents and reciprocal ranking strategies.
 *
 * <h2>RAG 阶段：检索后 / 文档合并（Retrieval / Document Join）</h2>
 * <p>
 * 当上游用 {@code QueryExpander} 把一个问题扩成多个查询，每个查询又可能命中多个数据源时，
 * 我们会得到一个<b>二维结构</b>：{@code Map<Query, List<List<Document>>>}——
 * 外层按"扩展出的子查询"分组，内层 {@code List<List<Document>>} 中的每个 List
 * 来自不同数据源。{@code DocumentJoiner} 的职责就是把这堆候选文档合并成最终的一个 {@code List<Document>}。
 *
 * <h2>实现要点</h2>
 * <ul>
 *   <li><b>去重</b>：同一文档可能被多个子查询召回，需要按 ID / 内容去重；</li>
 *   <li><b>重排</b>：常用 RRF（Reciprocal Rank Fusion，倒数排名融合）等策略合并多路排序；</li>
 *   <li><b>截断</b>：限制最终返回的 top-K 以控制 prompt 长度。</li>
 * </ul>
 * 默认实现：{@code ConcatenationDocumentJoiner}（按顺序拼接 + 简单去重）。
 *
 * @author Thomas Vitale
 * @since 1.0.0
 */
public interface DocumentJoiner extends Function<Map<Query, List<List<Document>>>, List<Document>> {

	/**
	 * Joins documents retrieved across multiple queries and daa sources.
	 * @param documentsForQuery 外层 key 为子查询，value 为来自多个数据源的多组文档
	 * @return 合并、去重、重排后的最终文档列表
	 */
	List<Document> join(Map<Query, List<List<Document>>> documentsForQuery);

	default List<Document> apply(Map<Query, List<List<Document>>> documentsForQuery) {
		return join(documentsForQuery);
	}

}
