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

package org.springframework.ai.rag.generation.augmentation;

import java.util.List;
import java.util.function.BiFunction;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

/**
 * A component for augmenting an input query with additional data, useful to provide a
 * large language model with the necessary context to answer the user query.
 *
 * <h2>RAG 阶段：生成 / 查询增强（Generation / Query Augmentation）</h2>
 * <p>
 * 这是 RAG 流水线的<b>"最后一公里"</b>：把检索到的文档<i>拼接</i>进用户原始查询，
 * 形成最终送给 LLM 的 Prompt。具体的拼接形式（PromptTemplate、是否含历史、空召回如何兜底等）
 * 由实现决定。
 *
 * <h3>典型行为</h3>
 * <ul>
 *   <li>把若干 {@link Document#getText()} 拼成 {@code context} 占位符的内容；</li>
 *   <li>套用一个固定模板（"基于下面的上下文回答问题，不要凭空捏造……"）；</li>
 *   <li>当文档列表为空时，决定是直接放行还是改写为"知识库外提示"。</li>
 * </ul>
 * 默认实现：{@link org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter}。
 *
 * @author Thomas Vitale
 * @since 1.0.0
 */
public interface QueryAugmenter extends BiFunction<Query, List<Document>, Query> {

	/**
	 * Augments the user query with contextual data.
	 * @param query 用户原始查询
	 * @param documents 检索得到的上下文文档
	 * @return 增强后的查询，{@code text()} 一般是已套模板、含上下文的最终 prompt 文本
	 */
	Query augment(Query query, List<Document> documents);

	default Query apply(Query query, List<Document> documents) {
		return augment(query, documents);
	}

}
