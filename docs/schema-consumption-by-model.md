# 各 ChatModel 消费 JSON Schema 的方式

> 本文覆盖 OpenAI / Anthropic / Mistral AI / Google GenAI / DeepSeek / Bedrock
> 六种主流模型从 `ToolDefinition.inputSchema()` 拿到 JSON Schema 字符串后，是
> 怎么处理成各模型 SDK 期待的形态并发送给上游的。

---

## 1. 总览表

| 模型 | 方法名 | 处理策略 | 关键后处理 |
|---|---|---|---|
| **OpenAI** | `OpenAiChatModel.getChatCompletionTools` | 解析为 `Map<String, Object>`，塞进 SDK builder | 加 `strict: true` |
| **Anthropic** | `AnthropicChatModel.toAnthropicTool` | 解析为 `Map`，手动拆 `properties` 和 `required` | — |
| **Mistral AI** | `MistralAiChatModel.getFunctionTools` | **直接传原始字符串**（不解析） | 解释了为什么 `JsonSchemaGenerator` 默认去 `format` |
| **Google GenAI** | `GoogleGenAiToolCallingManager.resolveToolDefinitions` | 解析后再二次转换 | 转 OpenAPI 格式 + `type` 字段大写 |
| **DeepSeek** | （类似 OpenAI） | 解析为 `Map` | — |
| **Bedrock Converse** | `BedrockProxyChatModel` | 解析为 `Map` | — |

---

## 2. OpenAI — 解析成 Map + `strict: true`

文件：`models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java`
调用：`getChatCompletionTools(List<ToolDefinition>, OpenAiChatOptions)`，第 977 行起

处理流程：

```java
List<ChatCompletionTool> tools = new ArrayList<>();

for (ToolDefinition def : toolDefs) {
    Map<String, Object> map = objectMapper.readValue(def.inputSchema(), Map.class);

    ChatCompletionTool tool = new ChatCompletionTool(
        new ChatCompletionTool.FunctionTool(
            new ChatCompletionFunctionTool.Function(
                def.name(),
                def.description(),
                new FunctionParameters(
                    objectMapper.writeValueAsString(map)         // 把 properties/type/required
                )                                                // 装进 SDK 类型
            )
        ).strict(true)                                          // ← OpenAI 新版要求 strict
    );
    tools.add(tool);
}
```

特点：

- 用 Jackson 把 JSON Schema 字符串反序列化成 `Map<String,Object>`
- 直接喂给 OpenAI SDK 的 `FunctionParameters`
- 加 `strict: true`，让 OpenAI 服务端走严格校验模式（拒绝未声明字段）
- **`format` 字段不需要**——OpenAI 接受，所以 Spring AI 生成时默认保留（但 `JsonSchemaGenerator.generateForMethodInput` 仍会剥掉，以防万一）

---

## 3. Anthropic — 手动拆 properties + required

文件：`models/spring-ai-anthropic/src/main/java/org/springframework/ai/anthropic/AnthropicChatModel.java`
调用：`toAnthropicTool(ToolDefinition)`，第 1253 行起

Anthropic SDK 不接受完整 JSON Schema，只接受显式构造的 `Tool.InputSchema` 对象，
带 `Map<String, Object> properties` 和 `List<String> required` 两个字段。

```java
public Tool toAnthropicTool(ToolDefinition def) {
    Map<String, Object> raw = objectMapper.readValue(def.inputSchema(), Map.class);

    Tool.InputSchema inputSchema = new Tool.InputSchema();

    if (raw.get("properties") instanceof Map properties) {
        inputSchema.properties().putAll(...);    // 逐个 putAll 到 SDK builder
    }

    if (raw.get("required") instanceof List required) {
        inputSchema.required().addAll(...);      // 逐个 addAll
    }

    return Tool.builder()
        .name(def.name())
        .description(def.description())
        .inputSchema(inputSchema)
        .build();
}
```

特点：

- 必须解析 JSON Schema 字符串为 Map
- 显式区分 `properties` 和 `required` 两个字段（不读 `additionalProperties`）
- `additionalProperties: false` 实际上是隐式默认——Anthropic SDK 会把任何没在 properties 里的字段直接拒绝

---

## 4. Mistral AI — **直接传字符串**

文件：`models/spring-ai-mistral-ai/src/main/java/org/springframework/ai/mistralai/MistralAiChatModel.java`
调用：`getFunctionTools(List<ToolDefinition>)`，第 515 行起

```java
List<FunctionTool> tools = new ArrayList<>();

for (ToolDefinition def : toolDefs) {
    FunctionTool tool = new FunctionTool(
        new FunctionTool.Function(
            def.name(),
            def.description(),
            def.inputSchema()                              // ← 直接传原始 string
        )
    );
    tools.add(tool);
}
```

**这正是 `JsonSchemaGenerator.generateForMethodInput` 默认去 `format` 字段的根本原因**：
Mistral 服务端在 2023-2024 的某次升级中开始拒绝 OpenAPI 的 `format`（`date-time` 之类）。
其他模型（OpenAI / Anthropic）能容忍，但 Mistral 不行。
所以 Spring AI 给**所有**模型生成 schema 时统一剥掉——这就是「为了一个模型，所有人让步」的设计代价。

---

## 5. Google GenAI / Vertex AI — 转 OpenAPI 格式 + type 大写

文件：`models/spring-ai-google-genai/src/main/java/org/springframework/ai/google/genai/schema/GoogleGenAiToolCallingManager.java`
调用：`resolveToolDefinitions(...)`，第 71 行起

Google Vertex AI（Gemini API）有自己的 schema 特点：

1. **`type` 字段要大写** —— `"Object"` 而不是 `"object"`
2. **要 OpenAPI 格式**（带 `format`、`enum` 等）
3. 字段顺序敏感

所以这里有**两步二次转换**：

```java
public List<ToolDefinition> resolveToolDefinitions(List<ToolDefinition> incoming) {
    return incoming.stream()
        .map(def -> {
            ObjectNode schema = (ObjectNode) objectMapper.readTree(def.inputSchema());

            // 步骤 1: 转 OpenAPI Schema (victools 的 JsonSchemaConverter)
            ObjectNode openApiSchema = JsonSchemaConverter.convertToOpenApiSchema(schema);

            // 步骤 2: type 字段值大写
            JsonSchemaGenerator.convertTypeValuesToUpperCase(openApiSchema);

            return ToolDefinition.builder()
                .name(def.name())
                .description(def.description())
                .inputSchema(openApiSchema.toPrettyString())
                .build();
        })
        .toList();
}
```

特点：

- **唯一**对 schema 做后处理再送给模型的实现
- 是 SchemaOption 的实际消费者：
  - `SchemaOption.UPPER_CASE_TYPE_VALUES` 的实际触发点
- 注意：第一次走 `JsonSchemaGenerator.generateForMethodInput`（已经去 `format`），
  第二次走 `JsonSchemaConverter.convertToOpenApiSchema`（**又会重新加回来**），
  最终既符合 Vertex AI 又满足其他模型。

---

## 6. DeepSeek

文件：`models/spring-ai-deepseek/src/main/java/org/springframework/ai/deepseek/DeepSeekChatModel.java`

DeepSeek API 兼容 OpenAI 接口，所以处理模式与 OpenAI 一致：

```java
Map<String, Object> map = objectMapper.readValue(def.inputSchema(), Map.class);
builder.putAdditionalProperty(name, new FunctionParameters(objectMapper.writeValueAsString(map)));
```

只是不会有 `strict: true` 这种 OpenAI 特有的开关。

---

## 7. Bedrock Converse

文件：`models/spring-ai-bedrock-converse/src/main/java/org/springframework/ai/bedrock/converse/BedrockProxyChatModel.java`

Bedrock Converse API 用 `toolSpec` 字段，每个工具需要 `toolSpec.inputSchema.json`。

```java
Map<String, Object> map = objectMapper.readValue(def.inputSchema(), Map.class);

ToolSpecification spec = ToolSpecification.builder()
    .name(def.name())
    .description(def.description())
    .inputSchema(ToolInputSchema.builder()
        .json(map)                            // ← Bedrock SDK 直接接 Map<String, Object>
        .build())
    .build();
```

---

## 8. 共同注意点

### 8.1 所有模型都假设拿到合法 JSON

`JsonSchemaGenerator` 输出 `toPrettyString()`——**已经是合法 JSON Schema**。
所有模型解析时直接 `objectMapper.readTree(...)` / `objectMapper.readValue(..., Map.class)`。

### 8.2 `additionalProperties: false` 的语义差异

| 模型 | 处理 `additionalProperties: false` |
|---|---|
| OpenAI (strict mode) | **强约束**——多余字段会被服务拒收 |
| Anthropic | 没显式处理，但 SDK 默认丢弃 |
| Mistral | 同 OpenAI strict |
| Vertex AI | 严格 |

> 这意味着：用 `JsonSchemaGenerator` 默认生成（`additionalProperties: false`），
> 基本上不会有「模型发了 schema 里没声明的字段」的情况发生。

### 8.3 大 schema 的传输

各家模型对单个工具 schema 的字节数有限制（典型 4KB-12KB）。
Spring AI 的 schema 是从方法签名推出来的，通常远小于这个阈值。
**几百个工具**才需要担心——这种情况请用 `ToolSearchToolCallingAdvisor`（渐进式披露）。

---

## 9. 添加新模型 SDK 时要做的

如果你在 Spring AI 里新增一个 ChatModel 适配器：

1. 在 `getChatCompletionTools`-类方法里：
   ```java
   Map<String, Object> map = objectMapper.readValue(def.inputSchema(), Map.class);
   ```
2. 把这个 Map 转成该 SDK 期待的形态（多数跟 OpenAI 类似）
3. 大概率**不需要**再加额外的 schema 后处理——`JsonSchemaGenerator` 已经把
   `format` / `additionalProperties` 这类问题统一处理掉了
4. 特殊情况请仿照 `GoogleGenAiToolCallingManager`，二次转换 schema

---

## 10. 调试技巧

如果工具调用时有奇怪问题：

```java
System.out.println("DEBUG schema: " + toolDef.inputSchema());
```

在 ChatModel 适配层之前打印 schema，确认：

- ✅ `properties` 存在
- ✅ 嵌套类型（`List<Foo>`）正确展开了 `items`
- ✅ 没有遗留的 `$ref` 指向不存在的 `#/$defs/...`
- ✅ `required` 数组名跟 `properties` 的 key 对得上

如果 schema 看起来不对，往回追到 `JsonSchemaGenerator` 的对应入口方法。
