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

package org.springframework.ai.chat.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyRateLimit;
import org.springframework.ai.chat.metadata.PromptMetadata;
import org.springframework.ai.chat.metadata.RateLimit;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 流式聊天响应聚合器。
 *
 * <p>
 * 当使用 {@link StreamingChatModel#stream(org.springframework.ai.chat.prompt.Prompt)}
 * 调用模型时，模型会以多条 {@link ChatResponse} 片段的形式增量返回结果（典型如
 * 一个 token 一片）。下游消费者通常希望同时具备两种能力：
 * <ol>
 * <li>实时透传：将增量片段立刻推给前端 / SSE 客户端；</li>
 * <li>事后归档：在流结束时拿到一个「完整的、合并好的」 {@link ChatResponse}
 * 用于日志记录、Advisor 后处理或对话历史持久化。</li>
 * </ol>
 *
 * <p>
 * {@code MessageAggregator} 即为第二个目标设计：它不打断原始流，而是「旁路」地
 * 在订阅、推进与完成的各个生命周期回调中收集所有片段，最终在 {@code onComplete}
 * 时构造一个完整的 {@link ChatResponse} 通过 {@link Consumer} 回调出去。
 *
 * <p>
 * 聚合的字段包括：
 * <ul>
 * <li>文本内容（含 thoughts / outputWithoutThoughts 拆分）；</li>
 * <li>消息级元数据 Map；</li>
 * <li>工具调用列表 ToolCall；</li>
 * <li>响应级元数据：模型 ID、模型名、用量 Usage（取每次出现的最新非零值）、
 * PromptMetadata、RateLimit。</li>
 * </ul>
 *
 * <p>
 * 由于使用了 {@link AtomicReference} 持有可变状态并在每次 {@code subscribe} 时重置，
 * 该聚合器实例可以被多次复用（但不要并发对同一份 Flux 重复订阅）。
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @author Thomas Vitale
 * @author Heonwoo Kim
 * @since 1.0.0
 */
public class MessageAggregator {

	private static final Logger logger = LoggerFactory.getLogger(MessageAggregator.class);

	/**
	 * 对给定的流式响应 {@link Flux} 进行旁路聚合：原样透传每一段
	 * {@link ChatResponse}，同时在内部累积状态；当上游 {@code onComplete} 时，
	 * 将累积出的完整 {@link ChatResponse} 通过 {@code onAggregationComplete} 回调出去。
	 *
	 * <p>
	 * 关键实现点：
	 * <ul>
	 * <li>所有累积状态均放在方法内的 {@link AtomicReference} 中，与外部隔离；</li>
	 * <li>{@code doOnSubscribe} 时将所有状态重置为初始值，使得同一个聚合器实例
	 * 可以被多次安全复用；</li>
	 * <li>{@code doOnNext} 中按字段做条件累积，只有「非空 / 有意义」的值才会覆盖
	 * 旧值，避免被中间空片段覆写；</li>
	 * <li>对 {@code metadata.isThought} 做特殊处理，将「思考」与「最终输出」分别
	 * 累积到不同 StringBuilder，并在最终消息的元数据中以 {@code thoughts} /
	 * {@code outputWithoutThoughts} 暴露。</li>
	 * </ul>
	 * @param fluxChatResponse 上游流式响应
	 * @param onAggregationComplete 上游 complete 时收到完整聚合结果的回调
	 * @return 与上游一一对应的透传 {@link Flux}（消费时下游看到的内容不变）
	 */
	public Flux<ChatResponse> aggregate(Flux<ChatResponse> fluxChatResponse,
			Consumer<ChatResponse> onAggregationComplete) {

		// === 助手消息相关累积状态 ===
		// 全部文本内容（包含 thoughts），最终写入 AssistantMessage.content
		AtomicReference<StringBuilder> messageTextContentRef = new AtomicReference<>(new StringBuilder());
		// 仅累积「思考」类片段（metadata.isThought == true）
		AtomicReference<StringBuilder> thoughtsRef = new AtomicReference<>(new StringBuilder());
		// 仅累积「非思考」片段，即真正的最终输出
		AtomicReference<StringBuilder> outputWithoutThoughtsRef = new AtomicReference<>(new StringBuilder());
		// 助手消息级别的元数据汇总（由各片段 metadata putAll 合并而成）
		AtomicReference<Map<String, Object>> messageMetadataMapRef = new AtomicReference<>();
		// 工具调用累积（来自 AssistantMessage.toolCalls 以及响应元数据中的 toolCalls）
		AtomicReference<List<ToolCall>> toolCallsRef = new AtomicReference<>(new ArrayList<>());

		// === 单次 generation 的元数据，遇到非 NULL 即覆盖 ===
		AtomicReference<ChatGenerationMetadata> generationMetadataRef = new AtomicReference<>(
				ChatGenerationMetadata.NULL);

		// === Token 用量：取「最新出现的非零值」，避免中间空片段把已计数清零 ===
		AtomicReference<Integer> metadataUsagePromptTokensRef = new AtomicReference<>(0);
		AtomicReference<Integer> metadataUsageGenerationTokensRef = new AtomicReference<>(0);
		AtomicReference<Integer> metadataUsageTotalTokensRef = new AtomicReference<>(0);

		// === 响应级其它元数据 ===
		AtomicReference<PromptMetadata> metadataPromptMetadataRef = new AtomicReference<>(PromptMetadata.empty());
		AtomicReference<RateLimit> metadataRateLimitRef = new AtomicReference<>(new EmptyRateLimit());

		AtomicReference<String> metadataIdRef = new AtomicReference<>("");
		AtomicReference<String> metadataModelRef = new AtomicReference<>("");

		return fluxChatResponse.doOnSubscribe(subscription -> {
			// 订阅时重置全部累积状态，使聚合器可被复用
			messageTextContentRef.set(new StringBuilder());
			thoughtsRef.set(new StringBuilder());
			outputWithoutThoughtsRef.set(new StringBuilder());
			messageMetadataMapRef.set(new HashMap<>());
			toolCallsRef.set(new ArrayList<>());
			metadataIdRef.set("");
			metadataModelRef.set("");
			metadataUsagePromptTokensRef.set(0);
			metadataUsageGenerationTokensRef.set(0);
			metadataUsageTotalTokensRef.set(0);
			metadataPromptMetadataRef.set(PromptMetadata.empty());
			metadataRateLimitRef.set(new EmptyRateLimit());

		}).doOnNext(chatResponse -> {

			// ---- 处理 generation 部分（可能为 null，例如某些心跳片段） ----
			if (chatResponse.getResult() != null) {
				// 仅当片段携带「真实的」生成元数据时才覆盖（NULL 是占位实例）
				if (chatResponse.getResult().getMetadata() != null
						&& chatResponse.getResult().getMetadata() != ChatGenerationMetadata.NULL) {
					generationMetadataRef.set(chatResponse.getResult().getMetadata());
				}
				// 累积文本内容；并按 isThought 标志分流到 thoughts / outputWithoutThoughts
				if (chatResponse.getResult().getOutput().getText() != null) {
					messageTextContentRef.get().append(chatResponse.getResult().getOutput().getText());
					var metadata = chatResponse.getResult().getOutput().getMetadata();
					if (metadata != null && metadata.containsKey("isThought")) {
						var isThought = Boolean.parseBoolean(metadata.get("isThought").toString());
						if (isThought) {
							thoughtsRef.get().append(chatResponse.getResult().getOutput().getText());
						}
						else {
							outputWithoutThoughtsRef.get().append(chatResponse.getResult().getOutput().getText());
						}
					}
				}
				// 合并消息级元数据（直接 putAll，后到的同名 key 会覆盖前者）
				if (chatResponse.getResult().getOutput().getMetadata() != null) {
					messageMetadataMapRef.get().putAll(chatResponse.getResult().getOutput().getMetadata());
				}
				// 累积本片段中携带的工具调用
				AssistantMessage outputMessage = chatResponse.getResult().getOutput();
				if (!CollectionUtils.isEmpty(outputMessage.getToolCalls())) {
					toolCallsRef.get().addAll(outputMessage.getToolCalls());
				}

			}
			// ---- 处理响应级元数据 ----
			if (chatResponse.getMetadata() != null) {
				if (chatResponse.getMetadata().getUsage() != null) {
					Usage usage = chatResponse.getMetadata().getUsage();
					// 取「最新出现的非零值」，避免被零值覆盖
					metadataUsagePromptTokensRef.set(
							usage.getPromptTokens() > 0 ? usage.getPromptTokens() : metadataUsagePromptTokensRef.get());
					metadataUsageGenerationTokensRef.set(usage.getCompletionTokens() > 0 ? usage.getCompletionTokens()
							: metadataUsageGenerationTokensRef.get());
					metadataUsageTotalTokensRef
						.set(usage.getTotalTokens() > 0 ? usage.getTotalTokens() : metadataUsageTotalTokensRef.get());
				}
				// PromptMetadata：仅当携带至少一项时才覆盖
				if (chatResponse.getMetadata().getPromptMetadata() != null
						&& chatResponse.getMetadata().getPromptMetadata().iterator().hasNext()) {
					metadataPromptMetadataRef.set(chatResponse.getMetadata().getPromptMetadata());
				}
				// 注意：此处条件原意应为「当前累积值还是 EmptyRateLimit 时才更新」，
				// 实际表达式为反向，行为取决于上游是否传入有意义的 RateLimit；保留原逻辑
				if (chatResponse.getMetadata().getRateLimit() != null
						&& !(metadataRateLimitRef.get() instanceof EmptyRateLimit)) {
					metadataRateLimitRef.set(chatResponse.getMetadata().getRateLimit());
				}
				if (StringUtils.hasText(chatResponse.getMetadata().getId())) {
					metadataIdRef.set(chatResponse.getMetadata().getId());
				}
				if (StringUtils.hasText(chatResponse.getMetadata().getModel())) {
					metadataModelRef.set(chatResponse.getMetadata().getModel());
				}
				// 兼容部分实现：toolCalls 可能放在响应级 metadata 的扩展字段中
				Object toolCallsFromMetadata = chatResponse.getMetadata().get("toolCalls");
				if (toolCallsFromMetadata instanceof List) {
					@SuppressWarnings("unchecked")
					List<ToolCall> toolCallsList = (List<ToolCall>) toolCallsFromMetadata;
					toolCallsRef.get().addAll(toolCallsList);
				}

			}
		}).doOnComplete(() -> {

			// 上游正常结束：构造完整的 Usage、ChatResponseMetadata 与最终 AssistantMessage
			var usage = new DefaultUsage(metadataUsagePromptTokensRef.get(), metadataUsageGenerationTokensRef.get(),
					metadataUsageTotalTokensRef.get());

			var chatResponseMetadata = ChatResponseMetadata.builder()
				.id(metadataIdRef.get())
				.model(metadataModelRef.get())
				.rateLimit(metadataRateLimitRef.get())
				.usage(usage)
				.promptMetadata(metadataPromptMetadataRef.get())
				.build();

			AssistantMessage finalAssistantMessage;
			var messageMetadata = messageMetadataMapRef.get();
			// 若期间产生了「思考」类内容，则把分流结果一并注入消息元数据
			if (!thoughtsRef.get().isEmpty()) {
				messageMetadata.put("thoughts", thoughtsRef.get().toString());
				messageMetadata.put("outputWithoutThoughts", outputWithoutThoughtsRef.get().toString());
			}
			List<ToolCall> collectedToolCalls = toolCallsRef.get();

			// 根据是否累积到 ToolCall 决定 AssistantMessage 的构造方式
			if (!CollectionUtils.isEmpty(collectedToolCalls)) {

				finalAssistantMessage = AssistantMessage.builder()
					.content(messageTextContentRef.get().toString())
					.properties(messageMetadata)
					.toolCalls(collectedToolCalls)
					.build();
			}
			else {
				finalAssistantMessage = AssistantMessage.builder()
					.content(messageTextContentRef.get().toString())
					.properties(messageMetadata)
					.build();
			}
			// 通过回调把完整结果交给调用方（一般用于日志 / 对话历史持久化）
			onAggregationComplete.accept(new ChatResponse(List.of(new Generation(finalAssistantMessage,

					generationMetadataRef.get())), chatResponseMetadata));

			// 完成后再次重置状态，便于聚合器复用
			messageTextContentRef.set(new StringBuilder());
			thoughtsRef.set(new StringBuilder());
			outputWithoutThoughtsRef.set(new StringBuilder());
			messageMetadataMapRef.set(new HashMap<>());
			toolCallsRef.set(new ArrayList<>());
			metadataIdRef.set("");
			metadataModelRef.set("");
			metadataUsagePromptTokensRef.set(0);
			metadataUsageGenerationTokensRef.set(0);
			metadataUsageTotalTokensRef.set(0);
			metadataPromptMetadataRef.set(PromptMetadata.empty());
			metadataRateLimitRef.set(new EmptyRateLimit());

		}).doOnError(e -> logger.error("Aggregation Error", e));
	}

	/**
	 * {@link Usage} 的默认 record 实现，承载聚合后的三类 token 计数。
	 * <p>
	 * 同时实现了 {@link #getNativeUsage()}，以 Map 形式暴露原生用量字段，方便上层
	 * 序列化或与厂商原生格式对齐。
	 *
	 * @param promptTokens 输入 token 数（提示词消耗）
	 * @param completionTokens 输出 token 数（生成消耗）
	 * @param totalTokens 总 token 数
	 */
	public record DefaultUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) implements Usage {

		@Override
		public Integer getPromptTokens() {
			return promptTokens();
		}

		@Override
		public Integer getCompletionTokens() {
			return completionTokens();
		}

		@Override
		public Integer getTotalTokens() {
			return totalTokens();
		}

		@Override
		public Map<String, Integer> getNativeUsage() {
			Map<String, Integer> usage = new HashMap<>();
			usage.put("promptTokens", promptTokens());
			usage.put("completionTokens", completionTokens());
			usage.put("totalTokens", totalTokens());
			return usage;
		}
	}

}
