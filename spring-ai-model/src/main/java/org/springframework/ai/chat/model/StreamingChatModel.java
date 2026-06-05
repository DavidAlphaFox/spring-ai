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

import java.util.Arrays;
import java.util.Optional;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.StreamingModel;

/**
 * 流式聊天模型接口。
 *
 * <p>
 * 该接口为支持「逐 token / 逐片段返回」的聊天模型提供统一的抽象，是 {@link ChatModel} 的流式版本。它继承自通用流式模型抽象
 * {@link StreamingModel}，并基于 Reactor {@link Flux} 暴露非阻塞、背压友好的响应序列。
 *
 * <p>
 * 接口被声明为 {@link FunctionalInterface @FunctionalInterface}，因此可以使用 Lambda
 * 表达式或方法引用快速实现一个流式模型，常见于测试 / Mock 场景： <pre>{@code
 * StreamingChatModel mock = prompt -> Flux.just(response1, response2);
 * }</pre>
 *
 * <p>
 * 同样提供了 {@code String} 与 {@code Message...} 两种便捷重载，将每段 {@link ChatResponse} 中的
 * {@link AssistantMessage} 文本平铺为 {@code Flux<String>}， 便于直接绑定到 SSE 端点或终端输出。
 *
 * @see ChatModel
 * @see StreamingModel
 * @see ChatResponse
 */
@FunctionalInterface
public interface StreamingChatModel extends StreamingModel<Prompt, ChatResponse> {

	/**
	 * 以单条用户文本为输入发起流式调用，并将每段响应映射为纯文本字符串。
	 * <p>
	 * 当某一段响应的 {@link Generation} 或 {@link AssistantMessage#getText()} 为 {@code null}
	 * 时，会被映射为空字符串而不是抛出 NPE，因此下游可以放心地直接 拼接结果。
	 * @param message 用户输入文本
	 * @return 增量文本片段流；订阅后会按到达顺序持续发射，模型完成时正常结束
	 */
	default Flux<String> stream(String message) {
		Prompt prompt = new Prompt(message);
		return stream(prompt).map(response -> Optional.ofNullable(response.getResult())
			.map(Generation::getOutput)
			.map(AssistantMessage::getText)
			.orElse(""));
	}

	/**
	 * 以一组 {@link Message} 作为上下文发起流式调用，并将响应映射为纯文本流。
	 * <p>
	 * 语义与 {@link #stream(String)} 相同，区别仅在于支持传入完整对话上下文。
	 * @param messages 构成会话上下文的消息序列
	 * @return 增量文本片段流
	 */
	default Flux<String> stream(Message... messages) {
		Prompt prompt = new Prompt(Arrays.asList(messages));
		return stream(prompt).map(response -> Optional.ofNullable(response.getResult())
			.map(Generation::getOutput)
			.map(AssistantMessage::getText)
			.orElse(""));
	}

	/**
	 * 流式调用的核心抽象方法，必须由具体实现类提供。
	 * <p>
	 * 实现方需要负责：
	 * <ul>
	 * <li>与底层模型 API 建立流式连接（如 SSE、WebSocket、gRPC streaming）；</li>
	 * <li>将平台原生事件包装成 {@link ChatResponse} 后逐段发射；</li>
	 * <li>正确处理取消、超时、错误传播以及背压。</li>
	 * </ul>
	 * @param prompt 完整提示词对象，含消息列表与调用选项
	 * @return 一个 {@link Flux}，按到达顺序发射每段增量 {@link ChatResponse}
	 */
	@Override
	Flux<ChatResponse> stream(Prompt prompt);

}
