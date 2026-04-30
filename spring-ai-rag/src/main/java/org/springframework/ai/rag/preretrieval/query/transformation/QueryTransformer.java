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

package org.springframework.ai.rag.preretrieval.query.transformation;

import java.util.function.Function;

import org.springframework.ai.rag.Query;

/**
 * A component for transforming the input query to make it more effective for retrieval
 * tasks, addressing challenges such as poorly formed queries, ambiguous terms, complex
 * vocabulary, or unsupported languages.
 *
 * <h2>RAG 阶段：检索前 / 查询改写（Pre-retrieval / Query Transformation）</h2>
 * <p>
 * 用户的原始问题往往不是好的检索 query：可能含指代（"它支持吗？"）、口语化表达、
 * 多语种、冗余词等。{@code QueryTransformer} 把"<i>一个 Query 改写成另一个更适合检索的 Query</i>"，
 * 输入与输出都是单个 {@link Query}（一对一）。Spring AI 内置了三种典型实现：
 * <ul>
 *   <li>{@code RewriteQueryTransformer}：基于 LLM 重写为更适合搜索引擎的形式；</li>
 *   <li>{@code CompressionQueryTransformer}：把多轮对话压缩为独立、自包含的查询；</li>
 *   <li>{@code TranslationQueryTransformer}：把查询翻译为指定目标语言（与文档库语种对齐）。</li>
 * </ul>
 * 多个 transformer 可以串成一条链——
 * {@code RetrievalAugmentationAdvisor} 会按 List 顺序依次 {@link #transform(Query)}。
 *
 * @author Thomas Vitale
 * @since 1.0.0
 */
public interface QueryTransformer extends Function<Query, Query> {

	/**
	 * Transforms the given query according to the implemented strategy.
	 * @param query 原始查询
	 * @return 改写后的查询
	 */
	Query transform(Query query);

	/** 适配 {@link Function} 的桥接方法，便于函数式组合。 */
	default Query apply(Query query) {
		return transform(query);
	}

}
