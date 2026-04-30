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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.model.ModelResponse;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

/**
 * AI 提供商返回的聊天补全（chat completion / generation）响应。
 *
 * <p>
 * {@code ChatResponse} 是 {@link ChatModel} 调用的最终返回值，封装了：
 * <ul>
 * <li>一个或多个 {@link Generation}（候选结果列表，对应 OpenAI {@code n>1} 的场景）；</li>
 * <li>一个响应级元数据 {@link ChatResponseMetadata}，包含模型 ID、用量统计、
 * 限流信息、提示元数据等。</li>
 * </ul>
 *
 * <p>
 * 类被设计为不可变对象：构造时会通过 {@link List#copyOf(java.util.Collection)}
 * 复制一份候选列表的快照，避免外部修改污染响应。
 *
 * <p>
 * 同时提供 {@link Builder} 风格的构造方式，便于在测试、流式聚合
 * （{@link MessageAggregator}）等场景下灵活地组装响应。
 *
 * @author Christian Tzolov
 * @author Mark Pollack
 * @author Soby Chacko
 * @author John Blum
 * @author Alexandros Pappas
 * @author Thomas Vitale
 */
public class ChatResponse implements ModelResponse<Generation> {

	/** 响应级别的元数据（模型名称、用量、限流、ID 等）。 */
	private final ChatResponseMetadata chatResponseMetadata;

	/**
	 * AI 提供商返回的生成结果列表，可能包含多个候选。
	 */
	private final List<Generation> generations;

	/**
	 * 不带元数据的构造器；元数据会被初始化为一个空的 {@link ChatResponseMetadata}。
	 * @param generations 模型返回的候选结果列表
	 */
	public ChatResponse(List<Generation> generations) {
		this(generations, new ChatResponseMetadata());
	}

	/**
	 * 完整构造器。
	 * <p>
	 * 行为：
	 * <ul>
	 * <li>{@code generations} 不允许为 {@code null}，会通过 {@link Assert} 校验；</li>
	 * <li>{@code chatResponseMetadata} 若为 {@code null} 会被替换为一个空实例，
	 * 保证 {@link #getMetadata()} 永不返回 {@code null}；</li>
	 * <li>内部使用 {@link List#copyOf} 复制不可变快照，确保线程安全与不可变性。</li>
	 * </ul>
	 * @param generations 候选结果列表（不可为 null）
	 * @param chatResponseMetadata 响应级元数据（可为 null）
	 */
	public ChatResponse(List<Generation> generations, ChatResponseMetadata chatResponseMetadata) {
		Assert.notNull(generations, "'generations' must not be null");
		this.chatResponseMetadata = Objects.requireNonNullElse(chatResponseMetadata, new ChatResponseMetadata());
		this.generations = List.copyOf(generations);
	}

	/**
	 * 获取一个新的 {@link Builder} 实例，用于通过链式 API 构造 {@code ChatResponse}。
	 * @return 新的 Builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 获取所有候选生成结果。
	 * <p>
	 * 当请求时设置了多个候选数（如 OpenAI 的 {@code n} 参数），列表大小可能 &gt; 1。
	 * @return 候选 {@link Generation} 列表，不可为 {@code null}
	 */
	@Override
	public List<Generation> getResults() {
		return this.generations;
	}

	/**
	 * 获取候选列表中的第一个 {@link Generation}，是最常用的快捷方法。
	 * @return 第一个候选；当列表为空时返回 {@code null}
	 */
	public @Nullable Generation getResult() {
		if (CollectionUtils.isEmpty(this.generations)) {
			return null;
		}
		return this.generations.get(0);
	}

	/**
	 * 获取响应级元数据，包括 token 用量、模型名、限流信息、ID 等。
	 * @return 永不为 {@code null} 的 {@link ChatResponseMetadata}
	 */
	@Override
	public ChatResponseMetadata getMetadata() {
		return this.chatResponseMetadata;
	}

	/**
	 * 判断模型是否在本次响应中请求执行工具（function / tool call）。
	 * <p>
	 * 只要任一候选 {@link Generation} 的 {@link AssistantMessage} 中含有 toolCall，
	 * 即返回 {@code true}。常用于 ToolCallingManager 决定是否进入工具执行循环。
	 * @return 存在工具调用返回 {@code true}，否则 {@code false}
	 */
	public boolean hasToolCalls() {
		if (CollectionUtils.isEmpty(this.generations)) {
			return false;
		}
		return this.generations.stream().anyMatch(generation -> generation.getOutput().hasToolCalls());
	}

	/**
	 * 判断本次响应中是否存在某个候选的完成原因（finish reason）落在给定集合内。
	 * <p>
	 * 比较时不区分大小写。常用于 ChatClient / Advisor 链中检测 STOP、LENGTH、
	 * TOOL_CALLS 等结束语义。
	 * @param finishReasons 期望匹配的完成原因集合（不可为 null）
	 * @return 存在匹配返回 {@code true}，否则 {@code false}
	 */
	public boolean hasFinishReasons(Set<String> finishReasons) {
		Assert.notNull(finishReasons, "finishReasons cannot be null");
		if (CollectionUtils.isEmpty(this.generations)) {
			return false;
		}
		return this.generations.stream().anyMatch(generation -> {
			var finishReason = (generation.getMetadata().getFinishReason() != null)
					? generation.getMetadata().getFinishReason() : "";
			return finishReasons.stream().map(String::toLowerCase).toList().contains(finishReason.toLowerCase());
		});
	}

	@Override
	public String toString() {
		return "ChatResponse [metadata=" + this.chatResponseMetadata + ", generations=" + this.generations + "]";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ChatResponse that)) {
			return false;
		}
		return Objects.equals(this.chatResponseMetadata, that.chatResponseMetadata)
				&& Objects.equals(this.generations, that.generations);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.chatResponseMetadata, this.generations);
	}

	/**
	 * {@link ChatResponse} 的链式构造器。
	 * <p>
	 * 主要用途：
	 * <ul>
	 * <li>在测试或工厂代码中以可读的方式逐步组装响应；</li>
	 * <li>在流式聚合（{@link MessageAggregator}）中将零散的元数据收敛为一个完整响应；</li>
	 * <li>基于已有 {@link ChatResponse}（{@link #from(ChatResponse)}）做拷贝再修改。</li>
	 * </ul>
	 */
	public static final class Builder {

		/** 待构建的候选结果列表，必须在 {@link #build()} 前显式设置。 */
		private @Nullable List<Generation> generations;

		/** 元数据子构建器，所有 metadata 相关方法都委托给它。 */
		private ChatResponseMetadata.Builder chatResponseMetadataBuilder;

		private Builder() {
			this.chatResponseMetadataBuilder = ChatResponseMetadata.builder();
		}

		/**
		 * 基于已有 {@link ChatResponse} 初始化当前 Builder（拷贝其 generations 与
		 * 全部元数据），便于在原响应基础上做局部修改。
		 * @param other 源响应
		 * @return 当前 Builder
		 */
		public Builder from(ChatResponse other) {
			this.generations = other.generations;
			return this.metadata(other.chatResponseMetadata);
		}

		/**
		 * 向元数据中追加单个键值对（用于自定义扩展字段）。
		 * @param key 键
		 * @param value 值
		 * @return 当前 Builder
		 */
		public Builder metadata(String key, Object value) {
			this.chatResponseMetadataBuilder.keyValue(key, value);
			return this;
		}

		/**
		 * 用一个完整的 {@link ChatResponseMetadata} 覆盖当前元数据。
		 * <p>
		 * 复制其标准字段（model、id、rateLimit、usage、promptMetadata），同时遍历
		 * 其扩展键值对一一注入子构建器。
		 * @param other 待复制的元数据
		 * @return 当前 Builder
		 */
		public Builder metadata(ChatResponseMetadata other) {
			this.chatResponseMetadataBuilder.model(other.getModel());
			this.chatResponseMetadataBuilder.id(other.getId());
			this.chatResponseMetadataBuilder.rateLimit(other.getRateLimit());
			this.chatResponseMetadataBuilder.usage(other.getUsage());
			this.chatResponseMetadataBuilder.promptMetadata(other.getPromptMetadata());
			Set<Map.Entry<String, Object>> entries = other.entrySet();
			for (Map.Entry<String, Object> entry : entries) {
				this.chatResponseMetadataBuilder.keyValue(entry.getKey(), entry.getValue());
			}
			return this;
		}

		/**
		 * 设置候选结果列表（必填）。
		 * @param generations 候选生成结果
		 * @return 当前 Builder
		 */
		public Builder generations(List<Generation> generations) {
			this.generations = generations;
			return this;

		}

		/**
		 * 完成构建。要求 {@code generations} 已被设置，否则抛出
		 * {@link IllegalArgumentException}。
		 * @return 不可变的 {@link ChatResponse} 实例
		 */
		public ChatResponse build() {
			Assert.notNull(this.generations, "'generations' must not be null");
			return new ChatResponse(this.generations, this.chatResponseMetadataBuilder.build());
		}

	}

}
