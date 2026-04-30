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

import java.util.Objects;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.model.ModelResult;

/**
 * 模型一次「生成」（generation）结果的载体。
 *
 * <p>
 * 一次模型调用（{@link ChatModel#call(org.springframework.ai.chat.prompt.Prompt)}）
 * 可能根据 {@code n} 参数返回多个候选结果，每个候选结果在框架中都会被封装为一个
 * {@code Generation} 实例。多个 {@code Generation} 共同构成一个 {@link ChatResponse}。
 *
 * <p>
 * 该类实现了通用的 {@link ModelResult} 抽象，输出类型固定为
 * {@link AssistantMessage}（即「助手消息」），并附带一个生成级别的元数据
 * {@link ChatGenerationMetadata}（包含完成原因 finishReason、内容过滤标记等）。
 *
 * @see ChatResponse
 * @see AssistantMessage
 * @see ChatGenerationMetadata
 */
public class Generation implements ModelResult<AssistantMessage> {

	/** 模型返回的助手消息内容（含文本及可能的工具调用 ToolCall 列表）。 */
	private final AssistantMessage assistantMessage;

	/** 该次生成对应的元数据，例如 finishReason、内容过滤结果等。 */
	private ChatGenerationMetadata chatGenerationMetadata;

	/**
	 * 构造一个无元数据的 {@code Generation}，元数据将被设置为 {@link ChatGenerationMetadata#NULL}
	 * 占位实例，避免外部判空。
	 * @param assistantMessage 模型生成的助手消息
	 */
	public Generation(AssistantMessage assistantMessage) {
		this(assistantMessage, ChatGenerationMetadata.NULL);
	}

	/**
	 * 构造一个携带元数据的 {@code Generation}。
	 * <p>
	 * 当传入的 {@code chatGenerationMetadata} 为 {@code null} 时，会自动回退为
	 * {@link ChatGenerationMetadata#NULL}，保证 {@link #getMetadata()} 的返回值
	 * 永远非空。
	 * @param assistantMessage 助手消息
	 * @param chatGenerationMetadata 生成级别的元数据，可为 {@code null}
	 */
	public Generation(AssistantMessage assistantMessage, ChatGenerationMetadata chatGenerationMetadata) {
		this.assistantMessage = assistantMessage;
		this.chatGenerationMetadata = chatGenerationMetadata != null ? chatGenerationMetadata
				: ChatGenerationMetadata.NULL;
	}

	/**
	 * 获取本次生成的输出，即助手消息。
	 * @return 助手消息对象，永不为 {@code null}（除非外部主动传入 null）
	 */
	@Override
	public AssistantMessage getOutput() {
		return this.assistantMessage;
	}

	/**
	 * 获取本次生成对应的元数据。
	 * <p>
	 * 注意：返回值永远非空，未提供时为 {@link ChatGenerationMetadata#NULL}。
	 * @return 生成级别元数据
	 */
	@Override
	public ChatGenerationMetadata getMetadata() {
		return this.chatGenerationMetadata;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Generation that)) {
			return false;
		}
		return Objects.equals(this.assistantMessage, that.assistantMessage)
				&& Objects.equals(this.chatGenerationMetadata, that.chatGenerationMetadata);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.assistantMessage, this.chatGenerationMetadata);
	}

	@Override
	public String toString() {
		return "Generation[" + "assistantMessage=" + this.assistantMessage + ", chatGenerationMetadata="
				+ this.chatGenerationMetadata + ']';
	}

}
