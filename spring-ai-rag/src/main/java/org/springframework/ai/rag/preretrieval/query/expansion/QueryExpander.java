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

package org.springframework.ai.rag.preretrieval.query.expansion;

import java.util.List;
import java.util.function.Function;

import org.springframework.ai.rag.Query;

/**
 * A component for expanding the input query into a list of queries, addressing challenges
 * such as poorly formed queries by providing alternative query formulations, or by
 * breaking down complex problems into simpler sub-queries.
 *
 * <h2>RAG 阶段：检索前 / 查询扩展（Pre-retrieval / Query Expansion）</h2>
 * <p>
 * 与 {@link org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer}（一对一）相对，
 * {@code QueryExpander} 是"<b>一对多</b>"：把单个 Query 变成多个 Query，
 * 后续会被并行送入 {@code DocumentRetriever}，最终结果再由 {@code DocumentJoiner} 合并。
 *
 * <h2>典型用途</h2>
 * <ul>
 *   <li><b>多视角召回（Multi-Query / RAG-Fusion）</b>：让 LLM 生成多个不同表达的同义查询，
 *       提高召回率，缓解单一向量相似度的偏差；</li>
 *   <li><b>子问题拆解</b>：把复杂问题（"对比 A 和 B 的优劣"）拆成子问题（"A 的优点"、"B 的缺点"…）分别检索。</li>
 * </ul>
 * 内置实现：{@code MultiQueryExpander}（基于 LLM 生成多版本查询）。
 *
 * @author Thomas Vitale
 * @since 1.0.0
 */
public interface QueryExpander extends Function<Query, List<Query>> {

	/**
	 * Expands the given query into a list of queries.
	 * @param query 原始查询
	 * @return 扩展出的一组查询，每个都会独立走一遍检索
	 */
	List<Query> expand(Query query);

	default List<Query> apply(Query query) {
		return expand(query);
	}

}
