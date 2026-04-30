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

package org.springframework.ai.chat.client;

import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.MimeType;

/**
 * Client to perform stateless requests to an AI Model, using a fluent API.
 * <p>
 * Use {@link ChatClient#builder(ChatModel)} to prepare an instance.
 *
 * <h2>核心概念</h2>
 * <p>
 * ChatClient 是 Spring AI 提供的高层流式（fluent）API，用于以"声明式"的方式
 * 与底层 {@link ChatModel} 交互。它本身是 <b>无状态（stateless）</b> 的：每次
 * {@code prompt()} 调用都会创建一个新的 {@link ChatClientRequestSpec}，
 * 多次调用之间不会保留对话历史。
 *
 * <h2>关于 Memory（会话记忆）</h2>
 * <p>
 * ChatClient 接口本身并不暴露任何"memory"方法。会话记忆能力在 Spring AI 中是
 * 通过 <b>Advisor 拦截器链</b>（参见 {@link Advisor}）来注入的，典型实现为
 * {@code MessageChatMemoryAdvisor} / {@code PromptChatMemoryAdvisor}，背后由
 * {@code ChatMemory} 与 {@code ChatMemoryRepository} 负责存储与检索历史消息。
 * 用法上：
 * <ul>
 *   <li>通过 {@link Builder#defaultAdvisors(Advisor...)} 在构建期注册全局记忆 Advisor；</li>
 *   <li>通过 {@link ChatClientRequestSpec#advisors(Advisor...)} 在单次请求时追加；</li>
 *   <li>通过 {@link AdvisorSpec#param(String, Object)} 传入会话标识（如
 *       {@code conversationId}）等运行期参数，使同一会话的多次请求共享历史。</li>
 * </ul>
 * 这种"通过 Advisor 注入"的设计让 ChatClient 保持核心无状态，而把记忆作为可插拔能力。
 *
 * <h2>关于 Tool（工具/函数调用）</h2>
 * <p>
 * ChatClient 支持模型的 Tool/Function Calling 能力。可在 Builder 上配置默认工具
 * （{@link Builder#defaultTools}、{@link Builder#defaultToolNames}、
 * {@link Builder#defaultToolCallbacks}），也可在每次请求时通过
 * {@link ChatClientRequestSpec#tools}、{@link ChatClientRequestSpec#toolNames}、
 * {@link ChatClientRequestSpec#toolCallbacks} 进行覆盖或追加。
 * 工具的几种来源：
 * <ul>
 *   <li><b>Java 对象 + {@code @Tool} 注解</b>：通过 {@code tools(Object...)} 传入；</li>
 *   <li><b>Bean 名称引用</b>：通过 {@code toolNames(String...)} 引用 Spring 容器中的工具 Bean；</li>
 *   <li><b>显式 ToolCallback</b>：通过 {@code toolCallbacks(ToolCallback...)} 传入手工构造的回调；</li>
 *   <li><b>ToolCallbackProvider</b>：例如 MCP（Model Context Protocol）服务端发现的远端工具集合。</li>
 * </ul>
 * 此外可以通过 {@link ChatClientRequestSpec#toolContext(Map)} 注入运行期上下文，
 * 工具实现可在执行时读取这些上下文（例如当前用户、会话 ID 等）。
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @author Josh Long
 * @author Arjen Poutsma
 * @author Thomas Vitale
 * @since 1.0.0
 */
public interface ChatClient {

	/**
	 * 使用默认（NOOP）的 ObservationRegistry 创建一个 ChatClient，最便捷的入口。
	 */
	static ChatClient create(ChatModel chatModel) {
		return create(chatModel, ObservationRegistry.NOOP);
	}

	/**
	 * 创建一个带可观测性注册表（用于 Micrometer 指标/追踪）的 ChatClient。
	 */
	static ChatClient create(ChatModel chatModel, ObservationRegistry observationRegistry) {
		return create(chatModel, observationRegistry, null, null);
	}

	/**
	 * 全参数版本：除了底层 {@link ChatModel} 与 ObservationRegistry，还可自定义
	 * ChatClient 与 Advisor 的观测命名约定。内部委托给 {@link #builder} 后立刻 build。
	 */
	static ChatClient create(ChatModel chatModel, ObservationRegistry observationRegistry,
			@Nullable ChatClientObservationConvention chatClientObservationConvention,
			@Nullable AdvisorObservationConvention advisorObservationConvention) {
		Assert.notNull(chatModel, "chatModel cannot be null");
		Assert.notNull(observationRegistry, "observationRegistry cannot be null");
		return builder(chatModel, observationRegistry, chatClientObservationConvention, advisorObservationConvention)
			.build();
	}

	/**
	 * 返回一个 {@link Builder}，可在构建期配置默认 system / user 文案、
	 * 默认 advisors（含 memory 类）、默认 tools 等，再调用 {@link Builder#build()}。
	 */
	static Builder builder(ChatModel chatModel) {
		return builder(chatModel, ObservationRegistry.NOOP, null, null);
	}

	static Builder builder(ChatModel chatModel, ObservationRegistry observationRegistry,
			@Nullable ChatClientObservationConvention chatClientObservationConvention,
			@Nullable AdvisorObservationConvention advisorObservationConvention) {
		Assert.notNull(chatModel, "chatModel cannot be null");
		Assert.notNull(observationRegistry, "observationRegistry cannot be null");
		return new DefaultChatClientBuilder(chatModel, observationRegistry, chatClientObservationConvention,
				advisorObservationConvention);
	}

	/**
	 * 开启一次新的请求构建：返回的 {@link ChatClientRequestSpec} 继承本 ChatClient
	 * 在 Builder 阶段配置的所有默认值（system/user/advisors/tools 等），可在其上继续追加或覆盖。
	 */
	ChatClientRequestSpec prompt();

	/**
	 * 以一段用户文本为起点开启请求，等价于 {@code prompt().user(content)}。
	 */
	ChatClientRequestSpec prompt(String content);

	/**
	 * 以已构造好的 {@link Prompt}（含消息列表与 ChatOptions）为起点开启请求。
	 */
	ChatClientRequestSpec prompt(Prompt prompt);

	/**
	 * Return a {@link ChatClient.Builder} to create a new {@link ChatClient} whose
	 * settings are replicated from the default {@link ChatClientRequestSpec} of this
	 * client.
	 */
	Builder mutate();

	interface PromptUserSpec {

		PromptUserSpec text(String text);

		PromptUserSpec text(Resource text, Charset charset);

		PromptUserSpec text(Resource text);

		PromptUserSpec params(Map<String, Object> p);

		PromptUserSpec param(String k, Object v);

		PromptUserSpec media(Media... media);

		PromptUserSpec media(MimeType mimeType, URL url);

		PromptUserSpec media(MimeType mimeType, Resource resource);

		PromptUserSpec metadata(Map<String, Object> metadata);

		PromptUserSpec metadata(String k, Object v);

	}

	/**
	 * Specification for a prompt system.
	 */
	interface PromptSystemSpec {

		PromptSystemSpec text(String text);

		PromptSystemSpec text(Resource text, Charset charset);

		PromptSystemSpec text(Resource text);

		PromptSystemSpec params(Map<String, Object> p);

		PromptSystemSpec param(String k, Object v);

		PromptSystemSpec metadata(Map<String, Object> metadata);

		PromptSystemSpec metadata(String k, Object v);

	}

	/**
	 * Advisor（顾问/拦截器）配置规范。
	 * <p>
	 * Advisor 是 ChatClient 调用链中的横切扩展点，作用类似于 Servlet Filter：
	 * 可以在请求送达模型 <i>之前</i> 改写 Prompt（例如把历史消息塞进上下文），
	 * 也可以在收到响应 <i>之后</i> 做后处理（例如把本轮对话写回记忆库）。
	 *
	 * <h3>典型用途——会话记忆（Memory）</h3>
	 * Spring AI 的"对话记忆"功能正是通过 Advisor 实现的：
	 * <ul>
	 *   <li>{@code MessageChatMemoryAdvisor}：把历史消息以独立 Message 形式追加到 Prompt 中（推荐）；</li>
	 *   <li>{@code PromptChatMemoryAdvisor}：把历史拼接进 system prompt 文本中；</li>
	 *   <li>{@code VectorStoreChatMemoryAdvisor}：基于向量检索的"长期记忆"，按相似度召回相关历史。</li>
	 * </ul>
	 *
	 * <h3>会话标识（conversationId）</h3>
	 * 记忆 Advisor 通常需要从运行期参数中读取 {@code conversationId}（或自定义键）来区分不同会话。
	 * 这正是 {@link #param(String, Object)} 的核心用途：
	 * <pre>{@code
	 * chatClient.prompt()
	 *     .user("你好")
	 *     .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "user-42"))
	 *     .call().content();
	 * }</pre>
	 */
	interface AdvisorSpec {

		/**
		 * 为 Advisor 链注入一个运行期参数。最常用于传入 {@code conversationId} 让记忆 Advisor 定位到正确的会话历史。
		 */
		AdvisorSpec param(String k, Object v);

		/**
		 * 批量注入多个运行期参数。
		 */
		AdvisorSpec params(Map<String, Object> p);

		/**
		 * 追加一组 Advisor 到本次请求的执行链中（不会清空已有的默认 Advisor）。
		 */
		AdvisorSpec advisors(Advisor... advisors);

		/**
		 * 追加一组 Advisor 到本次请求的执行链中（List 形式）。
		 */
		AdvisorSpec advisors(List<Advisor> advisors);

	}

	interface CallResponseSpec {

		<T> @Nullable T entity(ParameterizedTypeReference<T> type);

		<T> @Nullable T entity(StructuredOutputConverter<T> structuredOutputConverter);

		<T> @Nullable T entity(Class<T> type);

		ChatClientResponse chatClientResponse();

		@Nullable ChatResponse chatResponse();

		@Nullable String content();

		<T> ResponseEntity<ChatResponse, T> responseEntity(Class<T> type);

		<T> ResponseEntity<ChatResponse, T> responseEntity(ParameterizedTypeReference<T> type);

		<T> ResponseEntity<ChatResponse, T> responseEntity(StructuredOutputConverter<T> structuredOutputConverter);

	}

	interface StreamResponseSpec {

		Flux<ChatClientResponse> chatClientResponse();

		Flux<ChatResponse> chatResponse();

		Flux<String> content();

	}

	/**
	 * 单次请求构建器。所有 {@code prompt()} 方法都会返回此对象，可在其上链式配置
	 * 消息、advisors、tools、options 等，最终通过 {@link #call()} 同步获取结果，
	 * 或通过 {@link #stream()} 拿到一个流式 {@link Flux}。
	 */
	interface ChatClientRequestSpec {

		/**
		 * Return a {@link ChatClient.Builder} to create a new {@link ChatClient} whose
		 * settings are replicated from this {@link ChatClientRequest}.
		 */
		Builder mutate();

		/**
		 * 通过 {@link AdvisorSpec} 的回调式 API 配置 advisors 与运行期参数。
		 * <p>
		 * <b>这是配置会话记忆（memory）最常用的入口</b>：在这里既能追加 Advisor，
		 * 又能传入 {@code conversationId} 这样的运行期参数：
		 * <pre>{@code
		 * .advisors(a -> a
		 *     .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
		 *     .param(ChatMemory.CONVERSATION_ID, sessionId))
		 * }</pre>
		 */
		ChatClientRequestSpec advisors(Consumer<AdvisorSpec> consumer);

		/**
		 * 直接追加一组 Advisor。若不需要传运行期参数，这是最简洁的形式。
		 * 例如直接挂上记忆 Advisor：{@code .advisors(memoryAdvisor)}。
		 */
		ChatClientRequestSpec advisors(Advisor... advisors);

		/**
		 * 直接追加一组 Advisor（List 形式）。
		 */
		ChatClientRequestSpec advisors(List<Advisor> advisors);

		/**
		 * 在 Prompt 中追加若干消息（user / assistant / system / tool）。
		 * 注意：如果你只是想接续之前的对话，更推荐使用记忆 Advisor 而不是手工塞历史消息。
		 */
		ChatClientRequestSpec messages(Message... messages);

		ChatClientRequestSpec messages(List<Message> messages);

		/**
		 * 用一个 {@link ChatOptions.Builder} 来定制本次调用的模型参数（temperature、模型名等）。
		 */
		<B extends ChatOptions.Builder<?>> ChatClientRequestSpec options(B customizer);

		// ============================== Tool / 工具调用相关 ==============================
		// 下面这一组方法都是用来声明"模型可调用的工具"。它们之间是<b>累加</b>关系（追加，不覆盖默认）。
		// 当模型在响应中要求调用某个工具时，ChatClient 会自动路由到对应的回调执行，
		// 并把工具结果作为新一轮 message 再发给模型，直到模型给出最终回答。

		/**
		 * 通过 <b>Bean 名称</b> 引用 Spring 容器中已注册的工具。常用于和 Spring Boot 自动装配
		 * 的 {@code @Tool} 注解工具配合：在配置类中声明工具 Bean，再在请求时按名引用即可。
		 */
		ChatClientRequestSpec toolNames(String... toolNames);

		/**
		 * 直接传入<b>带 {@code @Tool} 注解的 Java 对象</b>，框架会反射扫描其方法并暴露给模型。
		 * 这是临时/局部工具最方便的注册方式，不需要先把对象注册到 Spring 容器。
		 */
		ChatClientRequestSpec tools(Object... toolObjects);

		/**
		 * 直接传入手工构造的 {@link ToolCallback}（最底层的工具抽象）。
		 * 当工具的入参/执行逻辑无法用 {@code @Tool} 简单描述时使用，例如动态生成的工具。
		 */
		ChatClientRequestSpec toolCallbacks(ToolCallback... toolCallbacks);

		ChatClientRequestSpec toolCallbacks(List<ToolCallback> toolCallbacks);

		/**
		 * 通过 {@link ToolCallbackProvider} 批量提供工具集合。
		 * 典型用例：MCP（Model Context Protocol）客户端在连接后会动态发现一组远端工具，
		 * 并以 Provider 的形式整体暴露给 ChatClient。
		 */
		ChatClientRequestSpec toolCallbacks(ToolCallbackProvider... toolCallbackProviders);

		/**
		 * 注入工具执行时的<b>运行期上下文</b>。这些键值对不会发给模型，只对工具实现可见——
		 * 工具方法可通过 {@code ToolContext} 参数读取，常用于传递当前用户、租户、tracing 信息等。
		 */
		ChatClientRequestSpec toolContext(Map<String, Object> toolContext);

		ChatClientRequestSpec system(String text);

		ChatClientRequestSpec system(Resource textResource, Charset charset);

		ChatClientRequestSpec system(Resource text);

		ChatClientRequestSpec system(Consumer<PromptSystemSpec> consumer);

		ChatClientRequestSpec user(String text);

		ChatClientRequestSpec user(Resource text, Charset charset);

		ChatClientRequestSpec user(Resource text);

		ChatClientRequestSpec user(Consumer<PromptUserSpec> consumer);

		/**
		 * 自定义模板渲染器（默认为 StTemplateRenderer，基于 StringTemplate 语法）。
		 */
		ChatClientRequestSpec templateRenderer(TemplateRenderer templateRenderer);

		/**
		 * 同步发起一次调用，返回 {@link CallResponseSpec}，可进一步取出 String、ChatResponse 或结构化实体。
		 */
		CallResponseSpec call();

		/**
		 * 以流式（SSE / Reactive Stream）方式发起调用，返回 {@link StreamResponseSpec}，
		 * 适合需要逐 token 返回内容、构建打字机效果的场景。
		 */
		StreamResponseSpec stream();

	}

	/**
	 * 创建 {@link ChatClient} 的可变构建器。
	 * <p>
	 * 这里所有以 {@code default*} 命名的方法都用于配置<b>默认值</b>：构建出的 ChatClient
	 * 在每次 {@code prompt()} 时都会把这些默认值作为起点。在单次请求中通过
	 * {@link ChatClientRequestSpec} 上的同名方法可以追加或覆盖这些默认值。
	 *
	 * <h3>关于默认 Advisor（含 memory）</h3>
	 * 把<b>记忆 Advisor</b> 注册为默认是最常见的做法：这样所有请求都会自动带上记忆能力，
	 * 调用方只需在请求时通过 {@code advisors(a -> a.param(...))} 传入会话标识即可。
	 *
	 * <h3>关于默认 Tool</h3>
	 * 同理，把工具注册为默认值，可以让 ChatClient 成为一个"自带工具集"的智能体（agent）。
	 * MCP 客户端常用此方式将远端发现到的工具批量注册为默认。
	 */
	interface Builder {

		/**
		 * 注册<b>默认 Advisor 链</b>。会话记忆通常就在这里挂载，例如：
		 * {@code .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())}
		 */
		Builder defaultAdvisors(Advisor... advisors);

		/**
		 * 通过回调式 API 配置默认 Advisor，可同时注入默认运行期参数。
		 */
		Builder defaultAdvisors(Consumer<AdvisorSpec> advisorSpecConsumer);

		Builder defaultAdvisors(List<Advisor> advisors);

		/**
		 * 配置默认的 ChatOptions（模型名、temperature、topP 等）。
		 */
		Builder defaultOptions(ChatOptions.Builder chatOptions);

		Builder defaultUser(String text);

		Builder defaultUser(Resource text, Charset charset);

		Builder defaultUser(Resource text);

		Builder defaultUser(Consumer<PromptUserSpec> userSpecConsumer);

		Builder defaultSystem(String text);

		Builder defaultSystem(Resource text, Charset charset);

		Builder defaultSystem(Resource text);

		Builder defaultSystem(Consumer<PromptSystemSpec> systemSpecConsumer);

		/**
		 * 配置默认模板渲染器（不配置则使用 StringTemplate 风格的默认实现）。
		 */
		Builder defaultTemplateRenderer(TemplateRenderer templateRenderer);

		// ============================== 默认 Tool 配置 ==============================
		// 与请求时的同名方法一一对应，区别仅在于这里设置的是"全局默认"。

		/**
		 * 默认通过 Bean 名称引用 Spring 容器中的工具。
		 */
		Builder defaultToolNames(String... toolNames);

		/**
		 * 默认通过 {@code @Tool} 注解的 Java 对象注册工具。
		 */
		Builder defaultTools(Object... toolObjects);

		/**
		 * 默认通过 {@link ToolCallback} 注册工具（最底层方式）。
		 */
		Builder defaultToolCallbacks(ToolCallback... toolCallbacks);

		Builder defaultToolCallbacks(List<ToolCallback> toolCallbacks);

		/**
		 * 默认通过 {@link ToolCallbackProvider} 批量注册工具，常用于 MCP 集成。
		 */
		Builder defaultToolCallbacks(ToolCallbackProvider... toolCallbackProviders);

		/**
		 * 默认的工具运行期上下文（不发给模型，只对工具实现可见）。
		 */
		Builder defaultToolContext(Map<String, Object> toolContext);

		/**
		 * 复制当前 Builder 的所有配置，得到一个相互独立的副本，便于在已有配置基础上派生不同变体。
		 */
		Builder clone();

		/**
		 * 完成配置并构造 ChatClient 实例。
		 */
		ChatClient build();

	}

}
