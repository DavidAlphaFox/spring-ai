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

/**
 * A repository for storing and retrieving chat messages.
 *
 * <h2>定位</h2>
 * <p>
 * {@code ChatMemoryRepository} 是 {@link ChatMemory} 的"<b>存储后端</b>"抽象。
 * 它只关心"<i>消息存到哪里、怎么按会话 ID 增删改查</i>"，不关心窗口、摘要等 高层策略。Spring AI 为它提供了多种实现：
 * <ul>
 * <li>{@link InMemoryChatMemoryRepository}：基于 {@code ConcurrentHashMap}
 * 的进程内实现，开发与测试用；</li>
 * <li>JDBC / MongoDB / Redis / Cassandra / Cosmos DB / Neo4j 等仓储实现，分别在
 * {@code memory/repository/...} 子模块中。</li>
 * </ul>
 *
 * <h2>注意</h2>
 * <p>
 * {@link #saveAll(String, List)} 是<b>整段替换</b>而非追加——这把"如何合并旧消息与新消息" 的责任明确推给了上层
 * {@link ChatMemory} 实现，存储层只做幂等的整段写入， 便于在分布式环境下保持一致性。
 *
 * @author Thomas Vitale
 * @since 1.0.0
 */
public interface ChatMemoryRepository {

	/** 列出所有已存在的会话 ID（一般用于运维/管理场景）。 */
	List<String> findConversationIds();

	/** 按会话 ID 读取该会话当前持久化的全部消息。 */
	List<Message> findByConversationId(String conversationId);

	/**
	 * Replaces all the existing messages for the given conversation ID with the provided
	 * messages.
	 * <p>
	 * <b>整段替换语义</b>：用 {@code messages} 完全覆盖该会话已有的消息列表， 上层负责先把旧消息和新消息合并好再传入。
	 */
	void saveAll(String conversationId, List<Message> messages);

	/** 删除该会话的全部历史。 */
	void deleteByConversationId(String conversationId);

}
