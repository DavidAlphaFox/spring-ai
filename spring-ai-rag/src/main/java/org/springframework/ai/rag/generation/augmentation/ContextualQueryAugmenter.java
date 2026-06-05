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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.util.PromptAssert;
import org.springframework.util.Assert;

/**
 * Augments the user query with contextual data from the content of the provided
 * documents.
 *
 * <h2>定位</h2>
 * <p>
 * Spring AI 提供的<b>默认 {@link QueryAugmenter} 实现</b>。它把检索得到的文档拼成
 * "上下文块"塞进一个固定结构的 PromptTemplate，最终输出形如：
 * <pre>
 * Context information is below.
 * ---------------------
 * &lt;文档1 文本&gt;
 * &lt;文档2 文本&gt;
 * ---------------------
 * Given the context information and no prior knowledge, answer the query.
 * ……
 * Query: &lt;原始问题&gt;
 * Answer:
 * </pre>
 *
 * <h2>对"幻觉"的防御</h2>
 * <p>
 * 默认模板里明确指示模型：<i>only based on context, no prior knowledge</i>，并要求
 * 不知道就答"不知道"。这是 RAG 项目降低幻觉率的关键工程实践——通过 Prompt 明示
 * "只用提供的上下文回答"。
 *
 * <p>
 * Example usage: <pre>{@code
 * QueryAugmenter augmenter = ContextualQueryAugmenter.builder()
 *    .allowEmptyContext(false)
 *    .build();
 * Query augmentedQuery = augmenter.augment(query, documents);
 * }</pre>
 *
 * @author Thomas Vitale
 * @since 1.0.0
 */
public final class ContextualQueryAugmenter implements QueryAugmenter {

	private static final Log logger = LogFactory.getLog(ContextualQueryAugmenter.class);

	/** 默认增强模板：要求模型仅基于上下文作答，是 RAG 防幻觉的标准范式。 */
	private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("""
			Context information is below.

			---------------------
			{context}
			---------------------

			Given the context information and no prior knowledge, answer the query.

			Follow these rules:

			1. If the answer is not in the context, just say that you don't know.
			2. Avoid statements like "Based on the context..." or "The provided information...".

			Query: {query}

			Answer:
			""");

	/** 当文档检索为空且不允许空上下文时使用的兜底模板：礼貌地告知用户问题超出知识库。 */
	private static final PromptTemplate DEFAULT_EMPTY_CONTEXT_PROMPT_TEMPLATE = new PromptTemplate("""
			The user query is outside your knowledge base.
			Politely inform the user that you can't answer it.
			""");

	/** 默认<b>不允许</b>空上下文——避免模型在没有依据时凭空作答（幻觉）。 */
	private static final boolean DEFAULT_ALLOW_EMPTY_CONTEXT = false;

	/**
	 * Default document formatter that just joins document text with newlines.
	 * <p>
	 * 默认文档拼接策略：把每个 Document 的纯文本用换行连成一段。如有需要（例如要带文档元数据、来源 URL），
	 * 可通过 Builder 注入自定义格式化函数。
	 */
	private static final Function<List<Document>, String> DEFAULT_DOCUMENT_FORMATTER = documents -> documents.stream()
		.map(Document::getText)
		.collect(Collectors.joining(System.lineSeparator()));

	private final PromptTemplate promptTemplate;

	private final PromptTemplate emptyContextPromptTemplate;

	private final boolean allowEmptyContext;

	private final Function<List<Document>, String> documentFormatter;

	public ContextualQueryAugmenter(@Nullable PromptTemplate promptTemplate,
			@Nullable PromptTemplate emptyContextPromptTemplate, @Nullable Boolean allowEmptyContext,
			@Nullable Function<List<Document>, String> documentFormatter) {
		this.promptTemplate = promptTemplate != null ? promptTemplate : DEFAULT_PROMPT_TEMPLATE;
		this.emptyContextPromptTemplate = emptyContextPromptTemplate != null ? emptyContextPromptTemplate
				: DEFAULT_EMPTY_CONTEXT_PROMPT_TEMPLATE;
		this.allowEmptyContext = allowEmptyContext != null ? allowEmptyContext : DEFAULT_ALLOW_EMPTY_CONTEXT;
		this.documentFormatter = documentFormatter != null ? documentFormatter : DEFAULT_DOCUMENT_FORMATTER;
		PromptAssert.templateHasRequiredPlaceholders(this.promptTemplate, "query", "context");
	}

	@Override
	public Query augment(Query query, List<Document> documents) {
		Assert.notNull(query, "query cannot be null");
		Assert.notNull(documents, "documents cannot be null");

		logger.debug("Augmenting query with contextual data");

		// 空上下文走兜底分支：要么放行原查询，要么改写为"知识库外"提示。
		if (documents.isEmpty()) {
			return augmentQueryWhenEmptyContext(query);
		}

		// 1. 把文档列表格式化成一段连续文本（默认换行拼接）。
		String documentContext = this.documentFormatter.apply(documents);

		// 2. 准备模板参数：{query} = 原始问题，{context} = 拼好的文档上下文。
		Map<String, Object> promptParameters = Map.of("query", query.text(), "context", documentContext);

		// 3. 渲染模板，得到"含上下文 + 用户问题"的最终 prompt 文本，包装成新的 Query 返回。
		return new Query(this.promptTemplate.render(promptParameters));
	}

	/**
	 * 处理"<b>检索为空</b>"的情况：根据 {@link #allowEmptyContext} 决定走哪一支。
	 * <ul>
	 *   <li>true：直接返回原始 Query，让模型用自有知识回答（适合作为兜底但有幻觉风险）；</li>
	 *   <li>false（默认）：用 {@link #emptyContextPromptTemplate} 渲染出一个"礼貌拒答"的提示。</li>
	 * </ul>
	 */
	private Query augmentQueryWhenEmptyContext(Query query) {
		if (this.allowEmptyContext) {
			logger.debug("Empty context is allowed. Returning the original query.");
			return query;
		}
		logger.debug("Empty context is not allowed. Returning a specific query for empty context.");
		return new Query(this.emptyContextPromptTemplate.render());
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable PromptTemplate promptTemplate;

		private @Nullable PromptTemplate emptyContextPromptTemplate;

		private @Nullable Boolean allowEmptyContext;

		private @Nullable Function<List<Document>, String> documentFormatter;

		public Builder promptTemplate(PromptTemplate promptTemplate) {
			this.promptTemplate = promptTemplate;
			return this;
		}

		public Builder emptyContextPromptTemplate(PromptTemplate emptyContextPromptTemplate) {
			this.emptyContextPromptTemplate = emptyContextPromptTemplate;
			return this;
		}

		public Builder allowEmptyContext(Boolean allowEmptyContext) {
			this.allowEmptyContext = allowEmptyContext;
			return this;
		}

		public Builder documentFormatter(Function<List<Document>, String> documentFormatter) {
			this.documentFormatter = documentFormatter;
			return this;
		}

		public ContextualQueryAugmenter build() {
			return new ContextualQueryAugmenter(this.promptTemplate, this.emptyContextPromptTemplate,
					this.allowEmptyContext, this.documentFormatter);
		}

	}

}
