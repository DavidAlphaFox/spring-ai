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

package org.springframework.ai.rag;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

/**
 * Represents a query in the context of a Retrieval Augmented Generation (RAG) flow.
 *
 * <h2>定位</h2>
 * <p>
 * {@code Query} 是 RAG 流水线中<b>贯穿始终的载体</b>：从用户原始问题开始，依次经过
 * 查询改写（{@code QueryTransformer}）、查询扩展（{@code QueryExpander}）、
 * 文档检索（{@code DocumentRetriever}）、合并（{@code DocumentJoiner}）、
 * 后处理（{@code DocumentPostProcessor}）、查询增强（{@code QueryAugmenter}）等阶段，
 * 每一步都可能产出新的 {@code Query} 实例（不可变 record）。
 *
 * @param text 当前查询文本（最终送入向量检索 / LLM 的内容）
 * @param history 对话历史，用于改写器在多轮对话场景下消解指代、补全上下文
 * @param context 自由扩展的上下文 Map，可在各阶段间传递元数据（如租户、过滤条件等）
 * @author Thomas Vitale
 * @since 1.0.0
 */
public record Query(String text, List<Message> history, Map<String, Object> context) {

	public Query {
		Assert.hasText(text, "text cannot be null or empty");
		Assert.notNull(history, "history cannot be null");
		Assert.noNullElements(history, "history elements cannot be null");
		Assert.notNull(context, "context cannot be null");
		Assert.noNullElements(context.keySet(), "context keys cannot be null");
	}

	/**
	 * 便捷构造：仅有查询文本，无历史和上下文。
	 */
	public Query(String text) {
		this(text, List.of(), Map.of());
	}

	public Builder mutate() {
		return new Builder().text(this.text).history(this.history).context(this.context);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable String text;

		private List<Message> history = List.of();

		private Map<String, Object> context = Map.of();

		private Builder() {
		}

		public Builder text(String text) {
			this.text = text;
			return this;
		}

		public Builder history(List<Message> history) {
			this.history = history;
			return this;
		}

		public Builder history(Message... history) {
			this.history = List.of(history);
			return this;
		}

		public Builder context(Map<String, Object> context) {
			this.context = context;
			return this;
		}

		public Query build() {
			Assert.hasText(this.text, "text cannot be null or empty");
			return new Query(this.text, this.history, this.context);
		}

	}

}
