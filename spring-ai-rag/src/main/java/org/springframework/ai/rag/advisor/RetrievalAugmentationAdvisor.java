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

package org.springframework.ai.rag.advisor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import reactor.core.scheduler.Scheduler;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.Assert;

/**
 * Advisor that implements common Retrieval Augmented Generation (RAG) flows using the
 * building blocks defined in the {@link org.springframework.ai.rag} package and following
 * the Modular RAG Architecture.
 *
 * <h2>定位：RAG 流水线的"总指挥"</h2>
 * <p>
 * 这是 Spring AI 把整个 <b>Modular RAG</b> 流程封装为一个 {@code ChatClient} Advisor 的核心实现。
 * 把它通过 {@code .advisors(retrievalAugmentationAdvisor)} 挂到 ChatClient 上之后，
 * 每次 prompt 调用都会自动经过下面 7 步流水线，把检索到的文档注入到用户消息再发给 LLM。
 *
 * <h2>七步流水线（{@link #before}）</h2>
 * <ol>
 *   <li><b>构造 Query</b>：把用户消息文本 + 历史 + 上下文 Map 包成 {@link Query}；</li>
 *   <li><b>查询改写（QueryTransformer 链）</b>：依次执行，每一步产出新 Query（一对一）；</li>
 *   <li><b>查询扩展（QueryExpander，可选）</b>：把单个 Query 扩成多个（一对多）；</li>
 *   <li><b>并行检索（DocumentRetriever）</b>：每个扩展查询并行走一遍检索，得到多组文档；</li>
 *   <li><b>合并（DocumentJoiner）</b>：把多组文档去重 / 重排 / 合并成一个 List；</li>
 *   <li><b>后处理（DocumentPostProcessor 链）</b>：精排、压缩、过滤，缓解 lost-in-the-middle 等问题；</li>
 *   <li><b>查询增强（QueryAugmenter）</b>：把文档拼到 PromptTemplate，得到最终送给 LLM 的 user message。</li>
 * </ol>
 * 响应阶段（{@link #after}）会把检索到的文档列表写入 {@link ChatResponse} 的 metadata，
 * 方便上层做引用、可观测性、调试。
 *
 * @author Christian Tzolov
 * @author Thomas Vitale
 * @since 1.0.0
 * @see <a href="http://export.arxiv.org/abs/2407.21059">arXiv:2407.21059</a>
 * @see <a href="https://export.arxiv.org/abs/2312.10997">arXiv:2312.10997</a>
 * @see <a href="https://export.arxiv.org/abs/2410.20878">arXiv:2410.20878</a>
 */
public final class RetrievalAugmentationAdvisor implements BaseAdvisor {

	/** Advisor 上下文 / ChatResponse metadata 中"检索到的文档列表"使用的 key。 */
	public static final String DOCUMENT_CONTEXT = "rag_document_context";

	/** 查询改写器链：可叠加多个（如先重写、再翻译），按顺序执行。 */
	private final List<QueryTransformer> queryTransformers;

	/** 查询扩展器：可选；启用后单个查询会被扩展为多个并行检索的子查询。 */
	private final @Nullable QueryExpander queryExpander;

	/** 文档检索器：必填；这是真正访问向量库 / 搜索引擎的组件。 */
	private final DocumentRetriever documentRetriever;

	/** 文档合并器：默认 {@link ConcatenationDocumentJoiner}，把多查询多源结果合一。 */
	private final DocumentJoiner documentJoiner;

	/** 检索后处理链：精排、过滤、压缩等。 */
	private final List<DocumentPostProcessor> documentPostProcessors;

	/** 查询增强器：默认 {@link ContextualQueryAugmenter}，把文档拼进 Prompt 模板。 */
	private final QueryAugmenter queryAugmenter;

	/** 用于并行调用 {@link DocumentRetriever} 的执行器（启用 QueryExpander 时尤其重要）。 */
	private final TaskExecutor taskExecutor;

	/** Reactor Scheduler：流式调用时切换到非阻塞调度，避免阻塞反应式管线的事件循环线程。 */
	private final Scheduler scheduler;

	/** Advisor 顺序：用于和其他 Advisor（如 memory）协调执行先后。 */
	private final int order;

	private RetrievalAugmentationAdvisor(@Nullable List<QueryTransformer> queryTransformers,
			@Nullable QueryExpander queryExpander, DocumentRetriever documentRetriever,
			@Nullable DocumentJoiner documentJoiner, @Nullable List<DocumentPostProcessor> documentPostProcessors,
			@Nullable QueryAugmenter queryAugmenter, @Nullable TaskExecutor taskExecutor, @Nullable Scheduler scheduler,
			@Nullable Integer order) {
		Assert.notNull(documentRetriever, "documentRetriever cannot be null");
		Assert.noNullElements(queryTransformers, "queryTransformers cannot contain null elements");
		this.queryTransformers = queryTransformers != null ? queryTransformers : List.of();
		this.queryExpander = queryExpander;
		this.documentRetriever = documentRetriever;
		this.documentJoiner = documentJoiner != null ? documentJoiner : new ConcatenationDocumentJoiner();
		this.documentPostProcessors = documentPostProcessors != null ? documentPostProcessors : List.of();
		this.queryAugmenter = queryAugmenter != null ? queryAugmenter : ContextualQueryAugmenter.builder().build();
		this.taskExecutor = taskExecutor != null ? taskExecutor : buildDefaultTaskExecutor();
		this.scheduler = scheduler != null ? scheduler : BaseAdvisor.DEFAULT_SCHEDULER;
		this.order = order != null ? order : 0;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * RAG 流水线的"请求前"钩子：拦截原始 {@link ChatClientRequest}，执行<b>七步流水线</b>，
	 * 最终返回一个把检索结果拼进 user message 的新请求。
	 */
	@Override
	public ChatClientRequest before(ChatClientRequest chatClientRequest, @Nullable AdvisorChain advisorChain) {
		// 复制一份上下文 Map：避免直接改动上游传入的 context。
		Map<String, Object> context = new HashMap<>(chatClientRequest.context());

		// === 步骤 0：从 ChatClientRequest 抽取信息，构造贯穿流水线的 Query 对象 ===
		String text = chatClientRequest.prompt().getUserMessage().getText();
		Query originalQuery = Query.builder()
			.text(Objects.requireNonNullElse(text, ""))
			.history(chatClientRequest.prompt().getInstructions())
			.context(context)
			.build();

		// === 步骤 1：查询改写——按顺序串过所有 QueryTransformer（如重写 / 压缩 / 翻译） ===
		Query transformedQuery = originalQuery;
		for (var queryTransformer : this.queryTransformers) {
			transformedQuery = queryTransformer.apply(transformedQuery);
		}

		// === 步骤 2：查询扩展——可选；扩展为多个子查询以提升召回率 ===
		List<Query> expandedQueries = this.queryExpander != null ? this.queryExpander.expand(transformedQuery)
				: List.of(transformedQuery);

		// === 步骤 3：并行检索——每个子查询提交到 taskExecutor 异步执行，最后 join 结果 ===
		// 结果结构：Map<Query, List<List<Document>>>
		//   外层 key 是子查询；
		//   内层 List<List<Document>> 中每个 List 代表"来自一个数据源"的一组文档（这里只接了一个 Retriever，
		//   所以外层包成单元素 List；如果用户继续在 Joiner 里合并多源，可在此扩展）。
		Map<Query, List<List<Document>>> documentsForQuery = expandedQueries.stream()
			.map(query -> CompletableFuture.supplyAsync(() -> getDocumentsForQuery(query), this.taskExecutor))
			.toList()
			.stream()
			.map(CompletableFuture::join)
			.collect(Collectors.toMap(Map.Entry::getKey, entry -> List.of(entry.getValue())));

		// === 步骤 4：合并——多查询、多源的候选集合并去重 / 重排成一个 List ===
		List<Document> documents = this.documentJoiner.join(documentsForQuery);

		// === 步骤 5：后处理——精排、过滤、压缩等，缓解长上下文 / 噪声问题 ===
		for (var documentPostProcessor : this.documentPostProcessors) {
			documents = documentPostProcessor.process(originalQuery, documents);
		}
		// 把最终文档列表写入上下文，after() 阶段会把它原样塞到 ChatResponse 的 metadata，便于上层引用和观测。
		context.put(DOCUMENT_CONTEXT, documents);

		// === 步骤 6：查询增强——把文档拼进 PromptTemplate，得到最终发送给 LLM 的 prompt 文本 ===
		// 注意这里用的是 originalQuery（用户原始问题），而不是改写 / 扩展后的版本——
		// 改写后的查询只服务于"召回"，模板里要还原用户真实问题，避免答非所问。
		Query augmentedQuery = this.queryAugmenter.augment(originalQuery, documents);

		// === 步骤 7：把增强后的文本写回 user message，并构造新的 ChatClientRequest 继续走 Advisor 链 ===
		return chatClientRequest.mutate()
			.prompt(chatClientRequest.prompt().augmentUserMessage(augmentedQuery.text()))
			.context(context)
			.build();
	}

	/**
	 * Processes a single query by routing it to document retrievers and collecting
	 * documents.
	 * <p>
	 * 单条子查询的检索逻辑：调用 {@link DocumentRetriever#retrieve(Query)}，
	 * 把 (query, documents) 装进 {@link Map.Entry} 返回，便于在并行流中保留"哪条查询命中了什么"的对应关系。
	 */
	private Map.Entry<Query, List<Document>> getDocumentsForQuery(Query query) {
		List<Document> documents = this.documentRetriever.retrieve(query);
		return Map.entry(query, documents);
	}

	/**
	 * RAG 流水线的"响应后"钩子：把 {@link #before} 阶段保存到 context 的检索文档列表
	 * 复制到 {@link ChatResponse} 的 metadata 中，让上层（业务代码、观测、UI）可以
	 * 直接拿到本次回答所引用的文档（用于"答案溯源"等场景）。
	 */
	@Override
	public ChatClientResponse after(ChatClientResponse chatClientResponse, @Nullable AdvisorChain advisorChain) {
		ChatResponse.Builder chatResponseBuilder;
		if (chatClientResponse.chatResponse() == null) {
			chatResponseBuilder = ChatResponse.builder();
		}
		else {
			chatResponseBuilder = ChatResponse.builder().from(chatClientResponse.chatResponse());
		}
		Object ctx = chatClientResponse.context().get(DOCUMENT_CONTEXT);
		if (ctx != null) {
			// 把本轮检索到的文档列表挂到响应 metadata，调用方可通过 chatResponse().metadata().get(DOCUMENT_CONTEXT) 取出。
			chatResponseBuilder.metadata(DOCUMENT_CONTEXT, ctx);
		}
		return ChatClientResponse.builder()
			.chatResponse(chatResponseBuilder.build())
			.context(chatClientResponse.context())
			.build();
	}

	@Override
	public Scheduler getScheduler() {
		return this.scheduler;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * 构造默认线程池：用于并行检索。
	 * <p>
	 * 关键点：注册了 {@link ContextPropagatingTaskDecorator}，把 MDC、请求作用域等
	 * 上下文从主线程传播到工作线程，避免日志 traceId、租户上下文等在异步检索时丢失。
	 */
	private static TaskExecutor buildDefaultTaskExecutor() {
		ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
		taskExecutor.setThreadNamePrefix("ai-advisor-");
		taskExecutor.setCorePoolSize(4);
		taskExecutor.setMaxPoolSize(16);
		taskExecutor.setTaskDecorator(new ContextPropagatingTaskDecorator());
		taskExecutor.initialize();
		return taskExecutor;
	}

	public static final class Builder {

		private @Nullable List<QueryTransformer> queryTransformers;

		private @Nullable QueryExpander queryExpander;

		private @Nullable DocumentRetriever documentRetriever;

		private @Nullable DocumentJoiner documentJoiner;

		private @Nullable List<DocumentPostProcessor> documentPostProcessors;

		private @Nullable QueryAugmenter queryAugmenter;

		private @Nullable TaskExecutor taskExecutor;

		private @Nullable Scheduler scheduler;

		private @Nullable Integer order;

		private Builder() {
		}

		public Builder queryTransformers(List<QueryTransformer> queryTransformers) {
			Assert.noNullElements(queryTransformers, "queryTransformers cannot contain null elements");
			this.queryTransformers = queryTransformers;
			return this;
		}

		public Builder queryTransformers(QueryTransformer... queryTransformers) {
			Assert.notNull(queryTransformers, "queryTransformers cannot be null");
			Assert.noNullElements(queryTransformers, "queryTransformers cannot contain null elements");
			this.queryTransformers = Arrays.asList(queryTransformers);
			return this;
		}

		public Builder queryExpander(QueryExpander queryExpander) {
			this.queryExpander = queryExpander;
			return this;
		}

		public Builder documentRetriever(DocumentRetriever documentRetriever) {
			this.documentRetriever = documentRetriever;
			return this;
		}

		public Builder documentJoiner(DocumentJoiner documentJoiner) {
			this.documentJoiner = documentJoiner;
			return this;
		}

		public Builder documentPostProcessors(List<DocumentPostProcessor> documentPostProcessors) {
			Assert.noNullElements(documentPostProcessors, "documentPostProcessors cannot contain null elements");
			this.documentPostProcessors = documentPostProcessors;
			return this;
		}

		public Builder documentPostProcessors(DocumentPostProcessor... documentPostProcessors) {
			Assert.notNull(documentPostProcessors, "documentPostProcessors cannot be null");
			Assert.noNullElements(documentPostProcessors, "documentPostProcessors cannot contain null elements");
			this.documentPostProcessors = Arrays.asList(documentPostProcessors);
			return this;
		}

		public Builder queryAugmenter(QueryAugmenter queryAugmenter) {
			this.queryAugmenter = queryAugmenter;
			return this;
		}

		public Builder taskExecutor(TaskExecutor taskExecutor) {
			this.taskExecutor = taskExecutor;
			return this;
		}

		public Builder scheduler(Scheduler scheduler) {
			this.scheduler = scheduler;
			return this;
		}

		public Builder order(Integer order) {
			this.order = order;
			return this;
		}

		public RetrievalAugmentationAdvisor build() {
			Assert.state(this.documentRetriever != null, "documentRetriever cannot be null");
			return new RetrievalAugmentationAdvisor(this.queryTransformers, this.queryExpander, this.documentRetriever,
					this.documentJoiner, this.documentPostProcessors, this.queryAugmenter, this.taskExecutor,
					this.scheduler, this.order);
		}

	}

}
