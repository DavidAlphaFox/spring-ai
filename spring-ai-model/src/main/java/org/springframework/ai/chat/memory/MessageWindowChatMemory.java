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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.util.Assert;

/**
 * A chat memory implementation that maintains a message window of a specified size,
 * ensuring that the total number of messages does not exceed the specified limit. When
 * the number of messages exceeds the maximum size, older messages are evicted.
 * <p>
 * Messages of type {@link SystemMessage} are treated specially: if a new
 * {@link SystemMessage} is added, all previous {@link SystemMessage} instances are
 * removed from the memory. Also, if the total number of messages exceeds the limit, the
 * {@link SystemMessage} messages are preserved while evicting other types of messages.
 *
 * <h2>核心策略：滑动窗口（Sliding Window）</h2>
 * <p>
 * 这是 Spring AI 提供的<b>默认 {@link ChatMemory} 策略实现</b>。它把"对话能记住多少" 简化为一个数字
 * {@link #maxMessages}：一旦总消息数超过窗口，就丢弃最早的非 system 消息。 这种策略的取舍：
 * <ul>
 * <li>优点：实现简单、行为可预期、Token 成本可控；</li>
 * <li>缺点：超出窗口的早期上下文会"失忆"，如需更长记忆请考虑摘要 / 向量检索类方案。</li>
 * </ul>
 *
 * <h2>SystemMessage 的特殊处理</h2>
 * <p>
 * SystemMessage 通常是"角色设定 / 全局指令"，应在整段对话中保持有效，因此本实现：
 * <ol>
 * <li><b>新增覆盖</b>：当本轮新增了一条新的 SystemMessage 时，旧的所有 SystemMessage 会被清理掉， 避免角色设定层层叠加；</li>
 * <li><b>淘汰豁免</b>：当总数超过窗口需要淘汰时，SystemMessage 不会被淘汰，只淘汰普通消息。</li>
 * </ol>
 *
 * @author Thomas Vitale
 * @author Ilayaperumal Gopinathan
 * @since 1.0.0
 */
public final class MessageWindowChatMemory implements ChatMemory {

	/** 默认窗口大小：保留最近 20 条消息。 */
	private static final int DEFAULT_MAX_MESSAGES = 20;

	/** 实际的存储后端，由 Builder 注入；默认是进程内的 {@link InMemoryChatMemoryRepository}。 */
	private final ChatMemoryRepository chatMemoryRepository;

	/** 滑动窗口大小：超过此数量后开始淘汰最早的非 system 消息。 */
	private final int maxMessages;

	private MessageWindowChatMemory(ChatMemoryRepository chatMemoryRepository, int maxMessages) {
		Assert.notNull(chatMemoryRepository, "chatMemoryRepository cannot be null");
		Assert.isTrue(maxMessages > 0, "maxMessages must be greater than 0");
		this.chatMemoryRepository = chatMemoryRepository;
		this.maxMessages = maxMessages;
	}

	@Override
	public void add(String conversationId, List<Message> messages) {
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		Assert.notNull(messages, "messages cannot be null");
		Assert.noNullElements(messages, "messages cannot contain null elements");

		// 三步走：1) 读旧消息；2) 在内存中合并 + 应用窗口策略；3) 整段写回（Repository 是替换语义）。
		List<Message> memoryMessages = this.chatMemoryRepository.findByConversationId(conversationId);
		List<Message> processedMessages = process(memoryMessages, messages);
		this.chatMemoryRepository.saveAll(conversationId, processedMessages);
	}

	@Override
	public List<Message> get(String conversationId) {
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		return this.chatMemoryRepository.findByConversationId(conversationId);
	}

	@Override
	public void clear(String conversationId) {
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		this.chatMemoryRepository.deleteByConversationId(conversationId);
	}

	/**
	 * 把"<b>已有历史</b>"和"<b>本轮新消息</b>"合并成最终要保存的列表，并应用窗口/SystemMessage 策略。
	 * @param memoryMessages 当前持久化的历史消息
	 * @param newMessages 本轮要追加的新消息
	 * @return 合并并裁剪后的最终消息列表
	 */
	private List<Message> process(List<Message> memoryMessages, List<Message> newMessages) {
		List<Message> processedMessages = new ArrayList<>();

		// === 第一步：判断本轮是否引入了"全新的 SystemMessage" ===
		// 若是，则旧的 SystemMessage 应该被丢弃，避免角色设定层层叠加。
		Set<Message> memoryMessagesSet = new HashSet<>(memoryMessages);
		boolean hasNewSystemMessage = newMessages.stream()
			.filter(SystemMessage.class::isInstance)
			.anyMatch(message -> !memoryMessagesSet.contains(message));

		// === 第二步：保留旧消息，但根据上一步的判断选择性剔除旧 SystemMessage ===
		memoryMessages.stream()
			.filter(message -> !(hasNewSystemMessage && message instanceof SystemMessage))
			.forEach(processedMessages::add);

		// === 第三步：追加本轮的新消息 ===
		processedMessages.addAll(newMessages);

		// === 第四步：未超窗口直接返回 ===
		if (processedMessages.size() <= this.maxMessages) {
			return processedMessages;
		}

		// === 第五步：超窗口时，从前往后淘汰最早的非 SystemMessage，直到满足窗口大小 ===
		// SystemMessage 享有"淘汰豁免"，永远保留。
		int messagesToRemove = processedMessages.size() - this.maxMessages;

		List<Message> trimmedMessages = new ArrayList<>();
		int removed = 0;
		for (Message message : processedMessages) {
			if (message instanceof SystemMessage || removed >= messagesToRemove) {
				trimmedMessages.add(message);
			}
			else {
				removed++;
			}
		}

		return trimmedMessages;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private ChatMemoryRepository chatMemoryRepository = new InMemoryChatMemoryRepository();

		private int maxMessages = DEFAULT_MAX_MESSAGES;

		private Builder() {
		}

		public Builder chatMemoryRepository(ChatMemoryRepository chatMemoryRepository) {
			this.chatMemoryRepository = chatMemoryRepository;
			return this;
		}

		public Builder maxMessages(int maxMessages) {
			this.maxMessages = maxMessages;
			return this;
		}

		public MessageWindowChatMemory build() {
			return new MessageWindowChatMemory(this.chatMemoryRepository, this.maxMessages);
		}

	}

}
