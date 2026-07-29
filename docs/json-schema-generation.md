# JSON Schema 生成机制详解

> 本文档深入 `JsonSchemaGenerator` 内部，覆盖：
> - 底层依赖（victools）配置
> - 两条入口方法（`generateForMethodInput` vs `generateForType`）
> - 五项后处理（`$defs` 提升、移除 `format`、清理 `$defs`、注入 `additionalProperties`、构建 `required` 数组）
> - `SchemaOption` 的作用

---

## 1. 主类与依赖

- **类**：`org.springframework.ai.util.json.schema.JsonSchemaGenerator`
- **路径**：`spring-ai-model/src/main/java/org/springframework/ai/util/json/schema/JsonSchemaGenerator.java`（362 行）
- **底层库**：[`com.github.victools:jsonschema-generator`](https://github.com/victools/jsonschema-generator)

为什么用 victools：

| 候选 | 优 | 劣 |
|---|---|---|
| 手写 Jackson `JsonNode` 反射 | 控制最细 | 工作量大，难以与 `@Schema` 等生态对齐 |
| **victools** | 自动感知 Jackson / Swagger / Kotlin 注解 | 模板化输出，需要后处理 |
| swagger-core | 习惯 Spring 生态 | 已弃用、与 Draft 2020-12 不友好 |

Spring AI 选 victools，再叠一层自己的后处理补 victools 没做的几件事。

---

## 2. 静态初始化 — victools 配置块

`JsonSchemaGenerator` 是一个 `final` 类，里面塞了两个静态的 `SchemaGenerator`：

```java
private static final SchemaGenerator typeSchemaGenerator;
private static final SchemaGenerator subtypeSchemaGenerator;

static {
    SchemaGeneratorConfigBuilder builder = new SchemaGeneratorConfigBuilder(
        draft2020Dialect,                 // Draft 2020-12
        OptionPreset.PLAIN_JSON);         // 不要 OpenAPI 别名

    builder.with(new JacksonSchemaModule(
        JacksonSchemaModuleOption.RESPECT_JSONPROPERTY_REQUIRED,
        JacksonSchemaModuleOption.RESPECT_JSONPROPERTY_ORDER))
           .with(new Swagger2Module(                            // 读 @Schema
               Swagger2ModuleOption.SKIP_PROPERTY_DEPENDENCIES_RESOLUTION))
           .with(springAiSchemaModule)                         // 自家：读 @ToolParam
           .with(Option.EXTRA_OPEN_API_FORMAT_VALUES)          // 生成时保留 format
           .with(Option.PLAIN_DEFINITION_KEYS);                 // 使用 $defs 而非 definitions

    typeSchemaGenerator = new SchemaGenerator(builder.build());
    subtypeSchemaGenerator = new SchemaGenerator(builder
        .without(Option.SCHEMA_VERSION_INDICATOR)              // 子 schema 不带 $schema
        .build());
}
```

要点：

- **两个 generator 区别只在 `SCHEMA_VERSION_INDICATOR`**：根 schema 带 `"$schema": "https://json-schema.org/draft/2020-12/schema"`，子 schema 不带。这避免子 schema 自称是顶层 schema。
- **`JacksonSchemaModule`** — 让 victools 识 `@JsonProperty(required=)`、`@JsonPropertyDescription`
- **`Swagger2Module`** — 让 victools 识 `@Schema(description=, requiredMode=, required=)`
- **`SpringAiSchemaModule`** — Spring AI 自定义，仅用于**字段级**（`forFields()`），
  读 `@ToolParam(description=, required=)`，详见 [`toolparam-processing.md`](./toolparam-processing.md)
- **`EXTRA_OPEN_API_FORMAT_VALUES`** — 产生 `format` 字段（"date-time"、"uri" 等）。后面 `generateForMethodInput` 又会**手动剥掉**。

---

## 3. 两条入口方法

### 3.1 `generateForMethodInput(Method, SchemaOption...)`

```java
public static String generateForMethodInput(Method method, SchemaOption... schemaOptions)
```

用于 `@Tool` 注解方法。流程：

```
for each method parameter:
    ├─ name  = method.getParameters()[i].getName()
    ├─ type  = method.getGenericParameterTypes()[i]      // 保留泛型
    ├─ skip if type is ToolContext                     (java-side filter)
    ├─ skip if Kotlin suspend: last param is Continuation
    ├─ if isMethodParameterRequired(method, i):
    │      required.add(name)
    ├─ subSchema = subtypeSchemaGenerator.generateSchema(type)
    ├─ JsonSchemaUtils.hoistDefsToRoot(rootSchema, subSchema)
    ├─ subSchema.remove("format")                       (Mistral 兼容)
    ├─ description = getMethodParameterDescription(method, i)
    └─ if description: subSchema.put("description", description)
       properties.set(name, subSchema)
remove empty "$defs"
unless ALLOW_ADDITIONAL_PROPERTIES_BY_DEFAULT:
    forbidAdditionalProperties(rootSchema)               (递归注入)
return rootSchema.toPrettyString()
```

输出形如：

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "city": { "type": "string", "description": "City name" },
    "at":   { "type": "string", "description": "Time in ISO-8601 format" }
  },
  "required": ["city"],
  "additionalProperties": false
}
```

### 3.2 `generateForType(Type, SchemaOption...)`

```java
public static String generateForType(Type type, SchemaOption... schemaOptions)
```

用于 `Function<I,O>` 的输入类型、Record 类型、BeanOutputConverter 等。流程：

```
rootSchema = typeSchemaGenerator.generateSchema(type)
apply SchemaOption transformations:
    UPPER_CASE_TYPE_VALUES      → 把 "type" 字段值大写 ("Object" / "String")
    ALLOW_ADDITIONAL_PROPERTIES_BY_DEFAULT → 跳过 forbidAdditionalProperties
strip "format" if EXTRA_OPEN_API_FORMAT_VALUES was applied
return rootSchema.toPrettyString()
```

注意：这条路径**不经过** `JsonSchemaGenerator.isMethodParameterRequired()` /
`getMethodParameterDescription()`（那些是方法参数级的钩子）。字段级的 `@ToolParam`
读取完全交给 victools + `SpringAiSchemaModule`（详见 `toolparam-processing.md`）。

### 3.3 对照表

| 维度 | `generateForMethodInput` | `generateForType` |
|---|---|---|
| 调用场景 | `@Tool` 方法 | `Function<I,O>` 输入类型 / Record / `BeanOutputConverter` |
| 使用的 generator | `subtypeSchemaGenerator`（每个参数单独生成） | `typeSchemaGenerator`（整体生成） |
| `@ToolParam` 读取 | 直接读 `Parameter.getAnnotation()` | 走 victools `SpringAiSchemaModule`（读 `Field`） |
| 是否补 `description` | 是（最后一遍覆盖 victools 的 description） | 否（依赖 victools + `SpringAiSchemaModule` 的解析结果） |
| 是否去 `format` | 是（`remove("format")`） | 否（透传，需要时调用方自己处理） |
| 后处理顺序 | hoist → strip format → forbid | strip format → forbid（按选项） |
| `requiredByDefault` | 硬编码为 `true` | 可由模块构造选项改为 `false` |

---

## 4. 五项后处理详解

### 4.1 `JsonSchemaUtils.hoistDefsToRoot(rootSchema, subSchema)`

**为什么需要**：victools 默认每次 `generateSchema` 都生成「自洽」的子 schema——
它的 `$defs` 和 `$ref` 指向**子 schema 自身**。当你把这个子 schema 嵌到父 schema 的
`properties.X` 里时，原本指向 `#/$defs/...` 的 ref 全部失效。

**解决**：把 `$defs` 从子 schema 合并到根 schema，重写 ref 路径。

```java
// 子 schema 长这样（独立看是合法的）：
{
  "$defs": { "User": { ... } },
  "$ref": "#/$defs/User"
}

// hoist 后期望（嵌进 properties.user 之后）：
// 1) 把 $defs 上提到根
// 2) ref 仍指向 "#/$defs/User"，但现在它们指根里的 defs
```

冲突处理：如果根已经有同名 `$def`，使用 `Name`、`Name2`、`Name3` 后缀避免覆盖。

文件位置：`spring-ai-model/.../util/json/schema/JsonSchemaUtils.java`

### 4.2 去除 `format` 关键字

**只发生在 `generateForMethodInput`**。

原因：Mistral AI 不接受 OpenAPI 的 `format` 字段（`date-time`、`uri` 等）。
为了给所有模型同一份 schema，方法参数路径每次都剥掉。

文件位置：`JsonSchemaGenerator.java` 在 `forbidAdditionalProperties` 之前的循环里。

### 4.3 清理空 `$defs`

`generateForMethodInput` 在 hoist 完成后，删除值为空的 `$defs`：

```java
schema.remove(schema.findValue("$defs"));  // 或遍历 ObjectNode 删空对象
```

否则 JSON Schema 输出会有 `"$defs": {}`，某些严格校验的模型 SDK 会炸。

### 4.4 `forbidAdditionalProperties(schema)` —— 递归注入

**默认行为**（除非传 `ALLOW_ADDITIONAL_PROPERTIES_BY_DEFAULT`）：
对 schema 中**每一个**对象节点，注入 `"additionalProperties": false`。

实现要点：

```java
private static void forbidAdditionalProperties(JsonNode node) {
    if (node instanceof ObjectNode obj) {
        // Map<K,V> 在 json schema 里表示成 {"type":"object", "additionalProperties":{<V-schema>}}
        // 这里要看 additionalProperties 是不是 ref，否则会被错误地覆盖成 false
        if (obj.get("additionalProperties") == null) {
            obj.put("additionalProperties", false);
        }
        // 递归
        obj.fields().forEachRemaining(e -> forbidAdditionalProperties(e.getValue()));
    } else if (node instanceof ArrayNode arr) {
        arr.forEach(child -> forbidAdditionalProperties(child));
    }
}
```

`Map<String, Foo>` 的特殊情况：schema 表示成：

```json
{
  "type": "object",
  "additionalProperties": { "$ref": "#/$defs/Foo" }
}
```

这时不能盲目设成 `false`，否则模型就知道不能传任何 key。所以实现里要先看
`additionalProperties` 是否已经是合法 schema（不是 boolean）。

### 4.5 构建 `required` 数组

**只在 `generateForMethodInput`**。

`required` 数组的元素来源 = 「被 `isMethodParameterRequired(method, i)` 判断为
true 的参数名字」。判断详见 [`toolparam-processing.md`](./toolparam-processing.md)。

`generateForType` 路径下 `required` 由 victools 模块自行决定（`SpringAiSchemaModule`
+ `JacksonSchemaModule` + `Swagger2Module` 共同投票）。

---

## 5. `SchemaOption` 枚举

定义在 `JsonSchemaGenerator` 内部，仅两个：

```java
public enum SchemaOption {
    ALLOW_ADDITIONAL_PROPERTIES_BY_DEFAULT,  // 跳过 forbidAdditionalProperties
    UPPER_CASE_TYPE_VALUES                    // "type" 字段值大写（"Object" / "String" / "Array"）
}
```

### 5.1 `ALLOW_ADDITIONAL_PROPERTIES_BY_DEFAULT`

适用场景：

- `Function<I,O>` 输入类型是「开放 map」 (`Map<String, Object>`)
- 你希望模型可以塞额外字段
- Augmented tool（如 `AugmentedToolCallback` 已显式注入新字段，不想再被 `false` 覆盖）

调用示例：

```java
String schema = JsonSchemaGenerator.generateForType(
    MyOpenInput.class,
    JsonSchemaGenerator.SchemaOption.ALLOW_ADDITIONAL_PROPERTIES_BY_DEFAULT);
```

### 5.2 `UPPER_CASE_TYPE_VALUES`

适用场景：

- Google GenAI / Vertex AI 要求 `"type"` 字段值**首字母大写**（`"Object"` 而非 `"object"`）

调用方：`GoogleGenAiToolCallingManager.resolveToolDefinitions(...)` 在拿到默认
schema 后再调 `JsonSchemaGenerator.convertTypeValuesToUpperCase(schema)`。

---

## 6. 其他工具方法（`JsonSchemaUtils`）

文件：`spring-ai-model/.../util/json/schema/JsonSchemaUtils.java`

| 方法 | 作用 |
|---|---|
| `getJsonSchema(Type type)` | 返回 `ObjectNode`（不是 String），用于需要进一步修改 schema 树的场景 |
| `hoistDefsToRoot(ObjectNode root, ObjectNode sub)` | 见 §4.1 |
| `ensureValidInputSchema(String inputSchema)` | 当 schema 来自外部（MCP / 第三方工具），保证至少有 `"type":"object"` 和 `"properties"` |

`ensureValidInputSchema` 用途举例：MCP 服务返回的 schema 可能只有 `"type":"object"`，没有
`properties`，模型发起的 tool_call 会变成 `{}`。`ensureValidInputSchema` 会兜底补
`properties` 字段。

---

## 7. 测试样例

文件：`spring-ai-model/src/test/java/org/springframework/ai/util/json/JsonSchemaGeneratorTests.java`

值得关注的测试：

| 测试 | 行号 | 覆盖什么 |
|---|---|---|
| `generateSchemaForMethodWithToolParamAnnotations` | 94 | 显式 `@ToolParam`，验证 `description` / `required` 都生效 |
| `generateSchemaForMethodWhenParameterRequiredByDefault` | 122 | 没标注时默认 required=true |
| `generateSchemaForMethodWithOpenApiSchemaAnnotations` | 150 | `@Schema` 降级路径 |
| `generateSchemaForMethodWithJacksonAnnotations` | 201 | `@JsonProperty` 降级路径 |
| `generateSchemaForMethodWithNullableAnnotations` | 229 | `@Nullable` 强制非必填 |
| `generateSchemaForType(AnnotatedPerson.class)` | 538 | 走 FieldScope 路径（`generateForType`），record 组件上的 `@ToolParam` |

**修改生成器行为前**，应当先跑一遍这些测试。

Kotlin 路径单独测：`spring-ai-model/src/test/kotlin/org/springframework/ai/util/json/schema/JsonSchemaGeneratorKotlinTests.kt`

---

## 8. 修改 `JsonSchemaGenerator` 行为的检查清单

> 修改后请同时检查：

- [ ] `JsonSchemaGenerator.java` 的两条入口
- [ ] `JsonSchemaUtils.java` 的 `hoistDefsToRoot`、`ensureValidInputSchema`
- [ ] `SpringAiSchemaModule.java` 的字段级 resolution
- [ ] `AbstractSpringAiSchemaModule.java` 的 `checkRequired` 优先级链
- [ ] MCP 平行的 `McpJsonSchemaGenerator.java` + `McpSpringAiSchemaModule.java`
- [ ] `JsonSchemaGeneratorTests.java` + `McpJsonSchemaGeneratorTests.java`
- [ ] Google GenAI 转 OpenAPI 的 `JsonSchemaConverter.convertToOpenApiSchema(...)`

---

## 9. 进一步阅读

- victools 文档：[github.com/victools/jsonschema-generator](https://github.com/victools/jsonschema-generator)
- Draft 2020-12 规范：[json-schema.org/draft/2020-12](https://json-schema.org/draft/2020-12/schema)
- MCP 平行实现的差异：见 `mcp/mcp-annotations/.../utils/McpJsonSchemaGenerator.java`
