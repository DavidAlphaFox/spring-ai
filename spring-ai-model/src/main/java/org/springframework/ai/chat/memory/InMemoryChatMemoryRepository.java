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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

/**
 * An in-memory implementation of {@link ChatMemoryRepository}.
 * <p>
 * 基于 {@link ConcurrentHashMap} 的<b>进程内</b>仓储实现。优点：零依赖、最低延迟， 是开发、单测、Demo
 * 的默认选择。缺点：进程重启即丢失、无法跨实例共享， 因此<b>不适合生产环境</b>——生产建议改用 JDBC / Redis / Mongo 等持久化实现。
 *
 * @author Thomas Vitale
 * @since 1.0.0
 */
public final class InMemoryChatMemoryRepository implements ChatMemoryRepository {

	// key = conversationId，value = 该会话当前保留的消息列表。使用 ConcurrentHashMap 以支持并发写入不同会话。
	Map<String, List<Message>> chatMemoryStore = new ConcurrentHashMap<>();

	@Override
	public List<String> findConversationIds() {
		return new ArrayList<>(this.chatMemoryStore.keySet());
	}

	@Override
	public List<Message> findByConversationId(String conversationId) {
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		List<Message> messages = this.chatMemoryStore.get(conversationId);
		// 防御性拷贝：避免外部修改影响内部存储；不存在的会话返回空列表而非 null。
		return messages != null ? new ArrayList<>(messages) : List.of();
	}

	@Override
	public void saveAll(String conversationId, List<Message> messages) {
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		Assert.notNull(messages, "messages cannot be null");
		Assert.noNullElements(messages, "messages cannot contain null elements");
		// 整段替换语义：直接 put 覆盖。上层 ChatMemory 已合并好新旧消息。
		this.chatMemoryStore.put(conversationId, messages);
	}

	@Override
	public void deleteByConversationId(String conversationId) {
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		this.chatMemoryStore.remove(conversationId);
	}

}
