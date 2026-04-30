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

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.Model;

/**
 * 聊天模型（ChatModel）核心接口。
 *
 * <p>
 * 该接口是 Spring AI 聊天能力的顶层抽象，定义了与底层大语言模型（LLM）进行
 * 一问一答交互的统一编程模型。它继承自：
 * <ul>
 * <li>{@link Model}&lt;{@link Prompt}, {@link ChatResponse}&gt; —— 通用模型抽象，
 * 表示「输入 Prompt，输出 ChatResponse」的同步调用语义。</li>
 * <li>{@link StreamingChatModel} —— 流式聊天模型抽象，提供基于 Reactor
 * {@link Flux} 的增量式响应能力。</li>
 * </ul>
 *
 * <p>
 * 各厂商（OpenAI、Anthropic、Azure、Ollama 等）的具体实现类只需实现
 * {@link #call(Prompt)} 与 {@link #stream(Prompt)} 即可对外提供统一的聊天能力，
 * 上层应用（ChatClient、Advisor、Agent 等）可基于该接口编写与厂商无关的代码。
 *
 * <p>
 * 接口同时提供了一组便捷的默认方法（接收 {@code String}、{@code Message...}），
 * 适用于无需自定义 {@link ChatOptions} 的简单场景，避免每次都手动构造 {@link Prompt}。
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @see StreamingChatModel
 * @see Prompt
 * @see ChatResponse
 */
public interface ChatModel extends Model<Prompt, ChatResponse>, StreamingChatModel {

	/**
	 * 以单条用户文本为输入调用模型，是最常用的便捷入口。
	 * <p>
	 * 内部会将传入的字符串包装为一条 {@link UserMessage}，再封装为 {@link Prompt}
	 * 调用 {@link #call(Prompt)}。
	 * @param message 用户输入的文本内容
	 * @return 模型生成的文本结果；当模型未返回任何 {@link Generation} 时返回空字符串，
	 * 当 {@code Generation} 为 {@code null} 时返回 {@code null}
	 */
	default @Nullable String call(String message) {
		Prompt prompt = new Prompt(new UserMessage(message));
		Generation generation = call(prompt).getResult();
		return (generation != null) ? generation.getOutput().getText() : "";
	}

	/**
	 * 以一组 {@link Message}（系统消息、用户消息、助手消息等）作为输入调用模型。
	 * <p>
	 * 适用于需要传入上下文（多轮对话历史 / system prompt 等）但又不希望使用
	 * {@link ChatOptions} 的场景。
	 * @param messages 任意数量的 {@link Message}，按数组顺序构成会话上下文
	 * @return 模型生成的文本结果；语义同 {@link #call(String)}
	 */
	default @Nullable String call(Message... messages) {
		Prompt prompt = new Prompt(Arrays.asList(messages));
		Generation generation = call(prompt).getResult();
		return (generation != null) ? generation.getOutput().getText() : "";
	}

	/**
	 * 以完整的 {@link Prompt} 同步调用模型，由各厂商实现负责真正的 HTTP / RPC 调用。
	 * <p>
	 * 该方法是接口的核心抽象点：上面的便捷重载、ChatClient、Advisor 等最终都会
	 * 收敛到此方法。
	 * @param prompt 完整的提示词对象，包含消息列表及调用选项
	 * @return 模型返回的 {@link ChatResponse}，包含一个或多个 {@link Generation}
	 * 以及响应级元数据（如 token 用量、模型名等）
	 */
	@Override
	ChatResponse call(Prompt prompt);

	/**
	 * 返回当前 {@link ChatModel} 实例的默认 {@link ChatOptions}。
	 * <p>
	 * 当调用方没有在 {@link Prompt} 中显式指定选项时，框架将退回使用此默认值。
	 * 子类应覆写本方法以提供模型特有的默认参数（如默认 model、temperature 等）。
	 * @return 默认的 {@link ChatOptions}，永不为 {@code null}
	 */
	default ChatOptions getDefaultOptions() {
		return ChatOptions.builder().build();
	}

	/**
	 * 以流式方式调用模型，返回随时间产生的多段响应片段。
	 * <p>
	 * 接口在此提供一个抛出 {@link UnsupportedOperationException} 的默认实现，
	 * 这样不支持流式的实现类可以直接复用。注意：尽管接口同时继承了
	 * {@link StreamingChatModel}（其 {@code stream} 是抽象方法），但具体的
	 * {@link ChatModel} 实现类如果不打算支持流式，可以保留此默认实现。
	 * @param prompt 完整的提示词对象
	 * @return 一个 {@link Flux}，按到达顺序发射每个增量 {@link ChatResponse}
	 * @throws UnsupportedOperationException 当具体实现不支持流式调用时抛出
	 */
	default Flux<ChatResponse> stream(Prompt prompt) {
		throw new UnsupportedOperationException("streaming is not supported");
	}

}
