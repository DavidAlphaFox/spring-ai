# Spring AI Tools 系统全貌

> **目标读者**：已经会用 `@Tool` 注解写工具，但想搞清楚从「写下注解」到「模型看到 JSON
> Schema 并发起调用」这条端到端链路上，每一个关键类在哪里、做什么。

---

## 1. 鸟瞰图

```
┌────────────────────────────────────────────────────────────────────────────┐
│                          调用链路总览                                       │
└────────────────────────────────────────────────────────────────────────────┘

 ┌────────────────────────┐
 │ ① 声明工具              │
 │ @Tool on Method /       │  ← 用户代码
 │ Function<I,O>           │
 └────────┬───────────────┘
          │ 反射扫描
          ▼
 ┌────────────────────────┐
 │ ② Tool 发现             │
 │ MethodToolCallbackProvi-│  spring-ai-model/.../tool/method/
 │ der / ToolCallbacks.from│  spring-ai-model/.../support/ToolCallbacks.java
 └────────┬───────────────┘
          │ 构造 ToolDefinition
          ▼
 ┌────────────────────────────────────────────────────────────────────┐
 │ ③ Tool 定义 (ToolDefinition) —  name + description + inputSchema  │
 │   ↑                                              ↑                 │
 │   │                                              │                 │
 │ ToolUtils.getToolName() /                 JsonSchemaGenerator     │
 │ getToolDescription()                        .generateForMethodInput│
 │ (读 @Tool 注解)                              (读 @ToolParam + 类型) │
 └────────┬───────────────────────────────────────────┬─────────────┘
          │                                           │
          │                                           ▼
          │                              ┌────────────────────────────┐
          │                              │ ④ JSON Schema 字符串         │
          │                              │    Draft 2020-12             │
          │                              │  + additionalProperties:false│
          │                              │  − format 字段               │
          │                              └────────────┬───────────────┘
          │                                           │
          ▼                                           ▼
 ┌────────────────────────────────────────────────────────────────────┐
 │ ⑤ ChatModel 适配层                                                 │
 │  OpenAiChatModel / AnthropicChatModel / MistralAiChatModel /       │
 │  GoogleGenAiToolCallingManager / DeepSeekChatModel /               │
 │  BedrockProxyChatModel                                             │
 │   → 把 Schema 字符串解析后塞进各家 SDK 的 tool/function 参数        │
 └────────┬──────────────────────────────────────────────────────────┘
          │
          ▼
 ┌────────────────────────┐
 │ ⑥ 模型决定调用工具       │  ← 不在 Spring AI 控制范围内
 │ → 返回 tool_calls       │
 └────────┬───────────────┘
          │
          ▼
 ┌────────────────────────────────────────────────────────────────────┐
 │ ⑦ Tool 调用执行                                                  │
 │  ToolCallingManager.executeToolCalls()                             │
 │   → ToolCallbackResolver 找到 callback                              │
 │   → MethodToolCallback / FunctionToolCallback /                   │
 │      AugmentedToolCallback 执行                                    │
 │   → ToolCallResultConverter (默认 Jackson) 序列化返回              │
 │   → ToolExecutionExceptionProcessor 处理异常                       │
 └────────┬──────────────────────────────────────────────────────────┘
          │
          ▼
 ┌────────────────────────┐
 │ ⑧ 返回模型形成最终回答   │
 └────────────────────────┘
```

---

## 2. 主要类清单（按子系统分组）

### 2.1 注解层 — 用户写的

| 类 | 路径 | 作用 |
|---|---|---|
| `@Tool` | `spring-ai-model/.../tool/annotation/Tool.java` | 标记方法为工具，可挂在方法 / 元注解上 |
| `@ToolParam` | `spring-ai-model/.../tool/annotation/ToolParam.java` | 描述方法参数 / record 组件，`required` + `description` |

### 2.2 抽象接口层 — 模型契约

| 类 | 路径 | 作用 |
|---|---|---|
| `ToolCallback` | `spring-ai-model/.../tool/ToolCallback.java` | 一个工具的对外接口：`getToolDefinition()` + `call(...)` |
| `ToolDefinition` | `spring-ai-model/.../tool/definition/ToolDefinition.java` | 模型看到的契约：`name()`、`description()`、`inputSchema()` |
| `ToolMetadata` | `spring-ai-model/.../tool/definition/ToolMetadata.java` | `returnDirect` 等运行时开关 |
| `ToolCallbackProvider` | `spring-ai-model/.../tool/ToolCallbackProvider.java` | 给一堆 callback 做分组的 provider |
| `ToolCallResultConverter` | `spring-ai-model/.../tool/ToolCallResultConverter.java` | 函数式接口：把返回值序列化成字符串 |
| `DefaultToolCallResultConverter` | 同上 | Jackson 默认实现 |

### 2.3 反射 / 发现层

| 类 | 路径 | 作用 |
|---|---|---|
| `MethodToolCallback` | `spring-ai-model/.../tool/method/MethodToolCallback.java` | 用反射调用 `@Tool` 方法 |
| `MethodToolCallbackProvider` | `spring-ai-model/.../tool/method/MethodToolCallbackProvider.java` | 扫描对象中的 `@Tool` 方法 |
| `FunctionToolCallback` | `spring-ai-model/.../tool/function/FunctionToolCallback.java` | 把 `Function` / `Supplier` / `Consumer` 包成工具 |
| `ToolCallbacks` | `spring-ai-model/.../support/ToolCallbacks.java` | 便捷工具：`from(...)` 转 `ToolCallback[]` |
| `ToolUtils` | `spring-ai-model/.../tool/support/ToolUtils.java` | 读 `@Tool` 注解（仅 `@Tool`，**不读 `@ToolParam`**） |
| `ToolDefinitions` | `spring-ai-model/.../tool/support/ToolDefinitions.java` | `from(method)` 把方法装配成 `ToolDefinition` |
| `ToolInputSchemaAugmenter` | `spring-ai-model/.../tool/augment/ToolInputSchemaAugmenter.java` | 给 schema 注入额外字段（用于 `AugmentedToolCallback`） |
| `AugmentedToolCallback` | `spring-ai-model/.../tool/augment/AugmentedToolCallback.java` | 让工具看见「增强字段」（如 `innerThought`） |

### 2.4 JSON Schema 生成层（核心）

| 类 | 路径 | 作用 |
|---|---|---|
| `JsonSchemaGenerator` | `spring-ai-model/.../util/json/schema/JsonSchemaGenerator.java` | **主转换器**，封装 victools |
| `JsonSchemaUtils` | `spring-ai-model/.../util/json/schema/JsonSchemaUtils.java` | 后处理工具：`hoistDefsToRoot`、`ensureValidInputSchema` |
| `AbstractSpringAiSchemaModule` | `spring-ai-model/.../util/json/schema/AbstractSpringAiSchemaModule.java` | 字段级优先级链（`@ToolParam` → `@JsonProperty` → `@Schema` → `@Nullable`） |
| `SpringAiSchemaModule` | `spring-ai-model/.../util/json/schema/SpringAiSchemaModule.java` | 上面那个的具体子类，专门读 `@ToolParam` |
| `KotlinModule` | `spring-ai-model/.../model/KotlinModule.java` | Kotlin nullability 桥接（classpath 有 kotlin-reflect 才生效） |
| `SchemaType` | `spring-ai-model/.../util/json/schema/SchemaType.java` | 预留的枚举（JSON_SCHEMA / OPEN_API_SCHEMA），目前未驱动实际行为 |

### 2.5 执行回路层

| 类 | 路径 | 作用 |
|---|---|---|
| `ToolCallingManager` | `spring-ai-model/.../tool/calling/ToolCallingManager.java` | 协调「模型返回 tool_calls」→「找到 callback」→「执行」 |
| `ToolCallbackResolver` / `StaticToolCallbackResolver` | `spring-ai-model/.../tool/resolution/` | 按名字找 callback |
| `ToolExecutionException` / `ToolExecutionExceptionProcessor` | `spring-ai-model/.../tool/execution/` | 异常包装和处理 |
| `ToolCallingAdvisor` | `spring-ai-client-chat/.../advisor/` | 由 `ChatClient` 自动注册、驱动整个调用循环 |

### 2.6 模型适配层（消费 Schema）

| 模型 | 类 | 行号 |
|---|---|---|
| OpenAI | `OpenAiChatModel.getChatCompletionTools(...)` | `models/.../openai/OpenAiChatModel.java:977` |
| Anthropic | `AnthropicChatModel.toAnthropicTool(...)` | `models/.../anthropic/AnthropicChatModel.java:1253` |
| Mistral AI | `MistralAiChatModel.getFunctionTools(...)` | `models/.../mistralai/MistralAiChatModel.java:515` |
| Google GenAI | `GoogleGenAiToolCallingManager.resolveToolDefinitions(...)` | `models/.../google/genai/schema/GoogleGenAiToolCallingManager.java:71` |
| DeepSeek | `DeepSeekChatModel` | `models/.../deepseek/DeepSeekChatModel.java` |
| Bedrock Converse | `BedrockProxyChatModel` | `models/.../bedrock/converse/BedrockProxyChatModel.java` |

### 2.7 MCP 平行实现

| 类 | 路径 | 与 `@Tool` 体系的关系 |
|---|---|---|
| `@McpTool` / `@McpToolParam` | `mcp/mcp-annotations/.../annotation/` | 对应 `@Tool` / `@ToolParam` |
| `McpJsonSchemaGenerator` | `mcp/mcp-annotations/.../utils/McpJsonSchemaGenerator.java` | 对应 `JsonSchemaGenerator`，但读 `@McpToolParam`，跳过 MCP 框架类型，带缓存 |
| `McpSpringAiSchemaModule` | `mcp/mcp-annotations/.../utils/McpSpringAiSchemaModule.java` | 对应 `SpringAiSchemaModule`，**继承相同优先级链** |
| Sync/Async × 有状态/无状态 共 4 个 Provider | `mcp/mcp-annotations/.../provider/tool/` | 对应 `MethodToolCallbackProvider` |

---

## 3. 关键概念

### 3.1 `ToolDefinition` 与 `ToolCallback` 的区别

```
ToolDefinition   ←  模型看到的契约（只读）
                  name / description / inputSchema

ToolCallback     ←  工具的全部 = 定义 + 执行逻辑
                  getToolDefinition() + call(toolInput)
```

- 同一份 `ToolDefinition` 可以被多个 `ToolCallback` 实例共享。
- `FunctionToolCallback` 和 `MethodToolCallback` 是 Spring AI 自带的两个内置实现。

### 3.2 三条声明工具的入口

1. **`@Tool` 注解方法**（最高层）
   ```java
   class MyTools {
       @Tool(description = "...")
       public String doSomething(String arg) { ... }
   }
   ```

2. **`MethodToolCallback` 直接构造**（中等层，控制反射对象）
   ```java
   MethodToolCallback.builder()
       .toolDefinition(ToolDefinitions.from(method))
       .toolMethod(method)
       .toolObject(new MyTools())
       .build();
   ```

3. **`FunctionToolCallback` 包 `Function` / lambda**（最高灵活度）
   ```java
   FunctionToolCallback.builder("name", myFn)
       .description("...")
       .inputType(MyInput.class)
       .build();
   ```

### 3.3 两条 schema 生成路径

`JsonSchemaGenerator` 暴露两条入口方法，走的是 victools，但**附加逻辑不一样**：

| 方法 | 用途 | 触发点 |
|---|---|---|
| `generateForMethodInput(Method)` | 处理 `@Tool` 方法的参数列表（**主流路径**） | `ToolDefinitions.from(method)` |
| `generateForType(Type)` | 处理类型 / `Function<I,O>` 的输入类型 | `FunctionToolCallback.Builder.build()` |

两者的「schema 后处理」共用（去 `format`、注入 `additionalProperties=false`、清理 `$defs`），
但**`@ToolParam` 处理细节不同**——见 [`toolparam-processing.md`](./toolparam-processing.md)。

---

## 4. 完整调用链示例

跟踪 `@Tool` 方法 `getWeather(String city, String at)`：

```
1. ChatClient.create(chatModel).tools(new WeatherTools())...
                                                              [ChatClient]
2. → DefaultChatClient 在请求时检查到一个 POJO 工具对象，
   把它转成 ToolCallback[] 用 ToolCallbacks.from(...) 实现
                                                              [ToolCallbacks.from]
3. → MethodToolCallbackProvider.builder().toolObjects(...).build()
                                                              [MethodToolCallbackProvider]
4. → 反射 getClass().getDeclaredMethods()，过滤出 @Tool 方法
5. → 对每个方法调 ToolDefinitions.builder(method)
                                                              [ToolDefinitions]
6. → ToolUtils.getToolName(method) 读 @Tool.name 或方法名     [ToolUtils]
   → ToolUtils.getToolDescription(method) 读 @Tool.description
   → JsonSchemaGenerator.generateForMethodInput(method)        [JsonSchemaGenerator]
7. → 遍历 method.getParameters()：
       读 @ToolParam / @JsonProperty / @Schema / @Nullable     [JsonSchemaGenerator]
       读 @ToolParam.description() / @JsonPropertyDescription / @Schema
       对每个参数类型用 victools 生成子 schema
       后处理：hoist $defs → 去 format → 注入 additionalProperties=false
   → 拼成 {"type":"object","properties":{...},"required":[...]} 字符串
8. → 构造 DefaultToolDefinition(name, description, inputSchema)
                                                              [DefaultToolDefinition]
9. → 装进 MethodToolCallback（持有 toolObject + method + def）
                                                              [MethodToolCallback]
10. ChatClient 默认注册 ToolCallingAdvisor，请求发送前把
    ToolCallback[] 转成各模型 SDK 期待的 tool/function 列表
                                                              [ToolCallingAdvisor]
11. 各 ChatModel 适配层（OpenAiChatModel.getChatCompletionTools 等）
    把 ToolDefinition.inputSchema() 字符串按各家 SDK 解析后塞进去
                                                              [ChatModel]
12. 模型返回 tool_calls →
    ToolCallingManager 找到 MethodToolCallback →
    反射 invoke → ToolCallResultConverter.convert(result) →
    结果塞回消息历史 → 再次发给模型 → 直到模型不再调用工具
                                                              [ToolCallingManager]
```

---

## 5. 关键不变量

| 不变量 | 含义 |
|---|---|
| 名字唯一 | 同一次 `ChatClient.call()` / `ChatModel.call()` 内，所有工具的 `name` 必须唯一；`MethodToolCallbackProvider` 重复 name 时直接抛异常 |
| 工具名格式 | 推荐 `snake_case`，字符集限制为字母数字、`_`、`-`、`.`（跨模型兼容性） |
| 单 ToolAdvisor | `DefaultChatClient` 强制要求 advisor 链中**至多一个** `ToolAdvisor`（包括自定义） |
| Schema Draft 版本 | 一律 2020-12 + `PLAIN_JSON` preset |
| `additionalProperties` | 默认 `false`，除非传 `ALLOW_ADDITIONAL_PROPERTIES_BY_DEFAULT` |
| MCP 不被自动扫描 | MCP provider 不会出现在 `StaticToolCallbackResolver` 里，避免启动时联网拉远程工具清单 |

---

## 下一步

- 想搞清楚 `JsonSchemaGenerator` 的所有后处理逻辑 → [`json-schema-generation.md`](./json-schema-generation.md)
- 想搞清楚 `@ToolParam` 在哪两条流水线上被读取、优先级是什么 → [`toolparam-processing.md`](./toolparam-processing.md)
- 想知道六家模型拿到 schema 后分别怎么做 → [`schema-consumption-by-model.md`](./schema-consumption-by-model.md)
- 想知道四种注册方式各自的取舍 → [`tool-registration-guide.md`](./tool-registration-guide.md)
