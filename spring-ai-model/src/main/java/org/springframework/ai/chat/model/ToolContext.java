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

import java.util.Collections;
import java.util.Map;

/**
 * 工具调用（function calling）执行时的上下文（Tool Context）。
 *
 * <p>
 * 在 Spring AI 的工具调用流程中，模型可以请求执行某个用户定义的函数 / 工具。 但工具的具体逻辑往往需要一些「请求级」的额外信息（如当前用户 ID、租户、 追踪
 * ID、缓存 key 等），这些信息既不应硬编码在工具实现中，也不适合作为 模型可见的入参暴露给 LLM。
 *
 * <p>
 * {@code ToolContext} 就是为这种场景设计的：调用方在构造 {@code ToolCallingChatOptions} 时通过
 * {@code toolContext} 字段传入一个 {@link Map}，框架在执行工具回调时再以 {@code ToolContext} 形式注入到工具实现中，
 * 工具内部即可读取这些键值对而无需经过模型。
 *
 * <p>
 * 实例为<b>不可变</b>对象：构造时通过 {@link Collections#unmodifiableMap} 对底层 Map
 * 做了只读包装，对外只暴露读视图，从而保证多线程环境下安全共享。
 *
 * <p>
 * 上下文 Map 中可放入任何与工具执行相关的信息，键值含义由调用方与工具实现共同约定。
 *
 * @author Christian Tzolov
 * @since 1.0.0
 */
public final class ToolContext {

	/** 只读视图下的上下文键值对集合，构造后不可修改。 */
	private final Map<String, Object> context;

	/**
	 * 使用给定 Map 构造一个 {@code ToolContext}，并对其做不可变包装。
	 * <p>
	 * 注意：此处仅做 {@code unmodifiableMap} 包装，并非深拷贝，因此外部如果继续 持有原始 Map
	 * 引用并修改它，包装视图也会观察到变化。如需彻底隔离，调用方 应自行传入一份不可变副本。
	 * @param context 工具上下文键值对
	 */
	public ToolContext(Map<String, Object> context) {
		this.context = Collections.unmodifiableMap(context);
	}

	/**
	 * 返回不可变的上下文 Map 视图，工具实现可从中读取调用方注入的运行时数据。
	 * @return 不可修改的 {@link Map} 视图
	 */
	public Map<String, Object> getContext() {
		return this.context;
	}

}
