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

package org.springframework.ai.chat.memory;

import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

/**
 * The contract for storing and managing the memory of chat conversations.
 *
 * <h2>核心定位</h2>
 * <p>
 * {@code ChatMemory} 是 Spring AI 中"<b>会话记忆</b>"的高层抽象。它负责按 <b>会话 ID（conversationId）</b>
 * 维护一段对话的消息列表，并对外提供 添加 / 读取 / 清空三种操作。它<b>不直接</b>被业务代码使用，而是被
 * {@code MessageChatMemoryAdvisor} 等 RAG/对话 Advisor 在每次请求前后调用：
 * <ul>
 * <li>请求前：通过 {@link #get(String)} 读取历史消息，拼接到 Prompt；</li>
 * <li>响应后：通过 {@link #add} 把新一轮的 user / assistant 消息回写。</li>
 * </ul>
 *
 * <h2>分层关系</h2>
 * <p>
 * {@code ChatMemory}（策略层，决定<i>保留哪些消息</i>，例如滑动窗口、摘要压缩等） →
 * {@link ChatMemoryRepository}（存储层，决定<i>消息存到哪里</i>， 例如内存、JDBC、Redis、MongoDB、Cassandra 等）。
 * 同一个策略可以搭配不同存储，反之亦然。参考实现：{@link MessageWindowChatMemory}。
 *
 * @author Christian Tzolov
 * @author Thomas Vitale
 * @since 1.0.0
 */
public interface ChatMemory {

	/**
	 * The key to retrieve the chat memory conversation id from the context.
	 * <p>
	 * Advisor 链上下文（{@code Map<String, Object>}）中会话 ID 的固定 key。 业务代码通过
	 * {@code .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))} 注入；记忆
	 * Advisor 再用此 key 取出会话 ID 并定位到对应的历史。
	 */
	String CONVERSATION_ID = "chat_memory_conversation_id";

	/**
	 * Save the specified message in the chat memory for the specified conversation.
	 * <p>
	 * 单条消息追加的便捷重载，内部委托给 {@link #add(String, List)}。
	 */
	default void add(String conversationId, Message message) {
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		Assert.notNull(message, "message cannot be null");
		this.add(conversationId, List.of(message));
	}

	/**
	 * Save the specified messages in the chat memory for the specified conversation.
	 * <p>
	 * 批量追加消息到指定会话。<b>具体策略由实现决定</b>——例如滑动窗口实现 会在追加后裁剪掉最早的消息，使总数不超过窗口大小。
	 */
	void add(String conversationId, List<Message> messages);

	/**
	 * Get the messages in the chat memory for the specified conversation.
	 * <p>
	 * 读取该会话当前保留的全部历史消息，供 Advisor 在请求前注入到 Prompt 中。
	 */
	List<Message> get(String conversationId);

	/**
	 * Clear the chat memory for the specified conversation.
	 * <p>
	 * 清空指定会话的历史，常用于"重新开始"或会话结束的清理。
	 */
	void clear(String conversationId);

}
