# Spring AI Tools 内部机制文档

本目录是对 Spring AI **Tools / Function Calling** 子系统的**开发者视角**深度文档，
与面向终端用户的官方文档（`spring-ai-docs/.../api/tools.adoc`）互为补充。

> 这里的每一篇都假设你已经能用 `@Tool` 写出能跑的代码，但想搞清楚
> 「这个工具是怎么被发现的、JSON Schema 是怎么生成的、`@ToolParam`
> 又是被谁读取的、各个 ChatModel 又怎么把它送出去的」。

---

## 目录

### 0. 系统全貌（先读这一篇）

- **[tool-system-overview.md](./tool-system-overview.md)**
  Tools 子系统的鸟瞰图：从 `@Tool` 方法被声明开始，到模型实际拿到 JSON Schema
  并发起调用为止的完整链路。包含主要类清单和一张大图。

### 1. JSON Schema 生成机制

- **[json-schema-generation.md](./json-schema-generation.md)**
  `JsonSchemaGenerator` 的内部实现细节：victools 配置、两条入口方法
  （`generateForMethodInput` vs `generateForType`）、`$defs` hoisting、
  `format` 关键字剥离、`additionalProperties: false` 注入等所有后处理逻辑。

### 2. `@ToolParam` 处理流水线

- **[toolparam-processing.md](./toolparam-processing.md)**
  `@ToolParam` 在哪里被读取？两条平行流水线（标准方法参数 vs
  Record 增强）和一份完整的「优先级链」。最后给出修改 `@ToolParam` 行为时
  必须同步修改的所有文件清单。

### 3. 工具注册方式

- **[tool-registration-guide.md](./tool-registration-guide.md)**
  四种工具注册方式的对比（直接 `.tools(...)`、`defaultTools(...)`、
  `ToolCallbacks.from(...)`、`MethodToolCallbackProvider` Bean），
  各自的适用场景与优劣。

### 4. 各模型对 Schema 的消费方式

- **[schema-consumption-by-model.md](./schema-consumption-by-model.md)**
  OpenAI / Anthropic / Mistral AI / Google GenAI / DeepSeek / Bedrock
  六家模型拿到 schema 字符串后分别怎么处理。

### 5. Java 反射知识点

- **[java-reflection-notes.md](./java-reflection-notes.md)**
  本次问答中提到的几个 Java 反射点解释：
  `method.getGenericParameterTypes()`、`instanceof Class<?>` pattern matching、
  `Nullness.forParameter()` 等。

---

## 关键文件位置速查

| 主题 | 路径（相对项目根） |
|---|---|
| `@Tool` 注解 | `spring-ai-model/src/main/java/org/springframework/ai/tool/annotation/Tool.java` |
| `@ToolParam` 注解 | `spring-ai-model/src/main/java/org/springframework/ai/tool/annotation/ToolParam.java` |
| `JsonSchemaGenerator` | `spring-ai-model/src/main/java/org/springframework/ai/util/json/schema/JsonSchemaGenerator.java` |
| `SpringAiSchemaModule` | `spring-ai-model/src/main/java/org/springframework/ai/util/json/schema/SpringAiSchemaModule.java` |
| `AbstractSpringAiSchemaModule` | `spring-ai-model/src/main/java/org/springframework/ai/util/json/schema/AbstractSpringAiSchemaModule.java` |
| `JsonSchemaUtils` | `spring-ai-model/src/main/java/org/springframework/ai/util/json/schema/JsonSchemaUtils.java` |
| `ToolUtils` | `spring-ai-model/src/main/java/org/springframework/ai/tool/support/ToolUtils.java` |
| `ToolDefinitions` | `spring-ai-model/src/main/java/org/springframework/ai/tool/support/ToolDefinitions.java` |
| `ToolCallbacks` | `spring-ai-model/src/main/java/org/springframework/ai/support/ToolCallbacks.java` |
| `MethodToolCallbackProvider` | `spring-ai-model/src/main/java/org/springframework/ai/tool/method/MethodToolCallbackProvider.java` |
| `MethodToolCallback` | `spring-ai-model/src/main/java/org/springframework/ai/tool/method/MethodToolCallback.java` |
| `FunctionToolCallback` | `spring-ai-model/src/main/java/org/springframework/ai/tool/function/FunctionToolCallback.java` |
| `AugmentedToolCallback` | `spring-ai-model/src/main/java/org/springframework/ai/tool/augment/AugmentedToolCallback.java` |
| `ToolInputSchemaAugmenter` | `spring-ai-model/src/main/java/org/springframework/ai/tool/augment/ToolInputSchemaAugmenter.java` |
| MCP 平行的生成器 | `mcp/mcp-annotations/.../utils/McpJsonSchemaGenerator.java` |

---

## 与官方文档的关系

- **本目录**面向「想修改 Spring AI 内部行为 / 想深度调试」的开发者，重点是**结构、链路、优先级**。
- **`spring-ai-docs/src/main/antora/modules/ROOT/pages/api/tools.adoc`** 面向**使用者**，
  重点是 API 用法、配置项、最佳实践。

两者不重复：本目录不解释「怎么写一个工具」，那部分看官方文档；
官方文档不解释「`@ToolParam` 在 JDK 反射层和 victools 层各被谁读取、为什么有两条流水线」，
那部分看本目录。
