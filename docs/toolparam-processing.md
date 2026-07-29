# `@ToolParam` 处理流水线详解

> 回答三个问题：
> 1. `@ToolParam` 在哪些地方被读取？
> 2. `@ToolParam` 的 `description` 和 `required` 与 Jackson / Swagger / `@Nullable`
>    一起出现时，谁优先？
> 3. 想改 `@ToolParam` 的行为，要改哪些文件？

---

## 1. 注解定义

文件：`spring-ai-model/src/main/java/org/springframework/ai/tool/annotation/ToolParam.java`

```java
@Target({ PARAMETER, FIELD, ANNOTATION_TYPE })   // ← 三个 target
@Retention(RUNTIME)
@Documented
public @interface ToolParam {
    boolean required() default true;
    String description() default "";
}
```

- `PARAMETER` — 方法参数（最常用）
- `FIELD` — 字段、record 组件（用于 Record 增强流水线）
- `ANNOTATION_TYPE` — 让它本身能作为元注解使用

---

## 2. 两条平行流水线

Spring AI 里**两个完全不同**的地方会读 `@ToolParam`，行为有差异：

```
┌─────────────────────────────────┐
│ 流水线 A：标准 @Tool 方法参数     │   ← 主流路径
└───────────────┬─────────────────┘
                │
                ▼
  JsonSchemaGenerator.generateForMethodInput(method)
       │
       ├─ isMethodParameterRequired(method, i)        直接读 Parameter.getAnnotation(ToolParam.class)
       └─ getMethodParameterDescription(method, i)    直接读 Parameter.getAnnotation(ToolParam.class)

┌─────────────────────────────────┐
│ 流水线 B：Record 增强（Augmented）│   ← 用于 AugmentedToolCallback
└───────────────┬─────────────────┘
                │
                ▼
  ToolInputSchemaAugmenter.toAugmentedArgumentTypes(recordClass)
       │
       └─ field.getAnnotation(ToolParam.class)        直接读 Field.getAnnotation
```

---

## 3. 流水线 A — 标准 `@Tool` 方法参数

**入口**：`JsonSchemaGenerator.generateForMethodInput(Method)`，由
`ToolDefinitions.from(method)` 触发。

### 3.1 `required` 判定 — 第 232-257 行

```java
private static boolean isMethodParameterRequired(Method method, int index) {
    Parameter p = method.getParameters()[index];

    var tp = p.getAnnotation(ToolParam.class);
    if (tp != null) return tp.required();                                    // ① @ToolParam

    var jp = p.getAnnotation(JsonProperty.class);
    if (jp != null) return jp.required();                                    // ② @JsonProperty

    var sa = p.getAnnotation(Schema.class);
    if (sa != null) {
        return sa.requiredMode() == Schema.RequiredMode.REQUIRED
            || sa.requiredMode() == Schema.RequiredMode.AUTO
            || sa.required();                                                // ③ @Schema
    }

    if (Nullness.forParameter(p) == Nullness.NULLABLE) return false;         // ④ @Nullable

    return PROPERTY_REQUIRED_BY_DEFAULT;                                     // ⑤ hard-coded true
}
```

**优先级（高到低）**：

| 序 | 注解 | 命中条件 | 含义 |
|---|---|---|---|
| 1 | `@ToolParam(required=)` | 直接读 | 返回 `annotation.required()` |
| 2 | `@JsonProperty(required=)` | 直接读 | 返回 `annotation.required()` |
| 3 | `@Schema(requiredMode=)` | `REQUIRED` 或 `AUTO` | 返回 `true` |
| 3 | `@Schema(required=)` | 显式 | 返回 `annotation.required()` |
| 4 | `@Nullable` / JSpecify | — | **强制 false**（覆盖前三条的一切） |
| 5 | (无标注) | — | 返回 `PROPERTY_REQUIRED_BY_DEFAULT`（**硬编码 true**） |

### 3.2 `description` 判定 — 第 270-289 行

```java
private static String getMethodParameterDescription(Method method, int index) {
    Parameter p = method.getParameters()[index];

    var tp = p.getAnnotation(ToolParam.class);
    if (tp != null && hasText(tp.description())) return tp.description();    // ① @ToolParam

    var jp = p.getAnnotation(JsonPropertyDescription.class);
    if (jp != null && hasText(jp.value())) return jp.value();                // ② @JsonPropertyDescription

    var sa = p.getAnnotation(Schema.class);
    if (sa != null && hasText(sa.description())) return sa.description();    // ③ @Schema

    return null;
}
```

**优先级（高到低）**：

| 序 | 注解 | 命中条件 |
|---|---|---|
| 1 | `@ToolParam(description=)` | 非空字符串 |
| 2 | `@JsonPropertyDescription(value=)` | 非空字符串 |
| 3 | `@Schema(description=)` | 非空字符串 |
| 4 | (无标注) | 返回 `null` |

注意：**`@Nullable` 不参与 description 判定**，它只影响 required。

### 3.3 顺序意义

`JsonSchemaGenerator` 在循环里**先**调用 `subtypeSchemaGenerator.generateSchema(type)`
让 victools 先生成子 schema（这一步 `SpringAiSchemaModule` 也会参与字段级描述，
但只是看 `@ToolParam`），**然后**再用 `getMethodParameterDescription(...)` 取到的字符串
覆盖子 schema 的 `"description"` 字段。

也就是说——**方法参数路径上，`@ToolParam.description` 是最终赢家**。
即便其他模块给了别的描述，最后都会被这层覆盖。

---

## 4. 流水线 B — Record 增强

**入口**：`ToolInputSchemaAugmenter.toAugmentedArgumentTypes(Class<? extends Record>)`，
由 `AugmentedToolCallback` 构造时触发。

文件：`spring-ai-model/.../tool/augment/ToolInputSchemaAugmenter.java` 第 51-75 行

```java
public static <T extends Record> List<AugmentedArgumentType> toAugmentedArgumentTypes(
        Class<T> recordClass) {

    return Arrays.stream(recordClass.getRecordComponents()).map(c -> {
        ToolParam toolParam = null;
        try {
            var field = recordClass.getDeclaredField(c.getName());
            toolParam = field.getAnnotation(ToolParam.class);            // ← 这里
        } catch (NoSuchFieldException ignored) {}

        return new AugmentedArgumentType(
            c.getName(),
            c.getGenericType(),
            toolParam != null ? toolParam.description() : "no description",  // ← 默认占位
            toolParam != null ? toolParam.required()    : false              // ← 默认 false
        );
    }).toList();
}
```

### 4.1 与流水线 A 的差异

| 维度 | 流水线 A（标准） | 流水线 B（Record 增强） |
|---|---|---|
| 何时读 `@ToolParam` | 每次调用 `generateForMethodInput` | 每次构造 `AugmentedToolCallback` |
| 优先级链 | `@ToolParam` → `@JsonProperty` → `@Schema` → `@Nullable` → 默认 true | **只有 `@ToolParam` 一种** |
| 缺注解时 `required` | 默认 `true`（硬编码） | 默认 **`false`** |
| 缺注解时 `description` | 默认 `null`（最后不写） | 默认 `"no description"`（占位字符串） |
| `@Nullable` 影响 | 是 | 否 |

设计原因：Record 增强场景下，**这些字段都是「给模型额外填的」**（如 `innerThought`），
它们大多是可选的，默认 `false` 是合理选择。

### 4.2 工作流程

```
AugmentedToolCallback 构造
  ├─ argumentType = 你的 Record 类
  ├─ 原 schema  = ToolDefinitions.from(method).inputSchema()   ← 普通 schema
  ├─ toAugmentedArgumentTypes(argumentType)                    ← 收集增强字段
  ├─ augmentToolInputSchema(originalSchema, augmentedArgs)     ← 合并
  │       │
  │       └─ 在 properties 里加新字段（来自 record components）
  └─ 把合并后的 schema 作为这个工具的 inputSchema 发给模型
```

然后模型返回的 JSON 同时包含原字段和新字段；框架把新字段塞给 `argumentConsumer`，
再把原字段透传给原工具方法。

---

## 5. 字段级路径（victools FieldScope）

有些场景，schema 是从 **某个 POJO 类型** 反推出来的（不是直接从方法参数）——
`generateForType(Type)` 走的就是这条路径。

这时 victools 会加载 `SpringAiSchemaModule`，对每个字段（field）调用：

### 5.1 `SpringAiSchemaModule` — 第 42-55 行

```java
@Override
protected String resolveToolParamDescription(MemberScope<?, ?> member) {
    var a = member.getAnnotationConsideringFieldAndGetter(ToolParam.class);
    if (a != null && hasText(a.description())) return a.description();
    return null;                  // null → 让 Jackson / Swagger 提供
}

@Override
protected Boolean resolveToolParamRequired(MemberScope<?, ?> member) {
    var a = member.getAnnotationConsideringFieldAndGetter(ToolParam.class);
    return a != null ? a.required() : null;     // null → 走父类优先级链
}
```

关键：**返回 `null` 不是「未定义」**，而是「放弃控制权，让其他模块（Jackson / Swagger）决定」。

### 5.2 `AbstractSpringAiSchemaModule.checkRequired()` — 第 97-134 行

完整的字段级 `required` 优先级链：

```java
private boolean checkRequired(MemberScope<?, ?> member) {
    Boolean tp = resolveToolParamRequired(member);                      // ① @ToolParam
    if (tp != null) return tp;

    var jp = member.getAnnotationConsideringFieldAndGetter(JsonProperty.class);
    if (jp != null) return jp.required();                               // ② @JsonProperty

    var sa = member.getAnnotationConsideringFieldAndGetter(Schema.class);
    if (sa != null) {
        return sa.requiredMode() == Schema.RequiredMode.REQUIRED
            || sa.requiredMode() == Schema.RequiredMode.AUTO
            || sa.required();                                          // ③ @Schema
    }

    Nullness n = Nullness.forField(/* member */);
    if (n == Nullness.NULLABLE) return false;                           // ④ @Nullable

    if (KotlinDetector.isKotlinType(/* type */)) return false;          // ⑤ Kotlin

    return this.requiredByDefault;                                     // ⑥ 模块构造选项
}
```

`requiredByDefault` 默认 `true`。但如果你**手动**这样构造：

```java
new SpringAiSchemaModule(SpringAiSchemaModule.Option.PROPERTY_REQUIRED_FALSE_BY_DEFAULT)
```

`requiredByDefault` 会变 `false`。

注意：`JsonSchemaGenerator` 的 `generateForMethodInput` 路径**不**走这个选项，
它是硬编码 `PROPERTY_REQUIRED_BY_DEFAULT = true`。

---

## 6. 完整优先级表

> 行 = 注解组合；列 = 行为；优先级 = 上面的胜出

| `@ToolParam(required=...)` | `@JsonProperty(required=...)` | `@Schema(required...)` | `@Nullable` | Kotlin 非空 | 流水线 A 实际 `required` | 字段级路径实际 `required` | 流水线 A 实际 `description` |
|---|---|---|---|---|---|---|---|
| `true` | (任意) | (任意) | (任意) | (任意) | **true** | **true** | `@ToolParam.description()` |
| (缺) | `true` | (任意) | (任意) | (任意) | true | true | `@JsonPropertyDescription` → `@Schema.description` |
| (缺) | (缺) | `required=true` | (任意) | (任意) | true | true | 同上 |
| (缺) | (缺) | `requiredMode=AUTO/REQUIRED` | (任意) | (任意) | true | true | 同上 |
| (缺) | (任意) | (任意) | yes | (任意) | **false** | **false** | 同上 |
| (缺) | (缺) | (缺) | (缺) | Kotlin 非空类型 | true | **false**（KotlinDetector 介入） | 同上 |
| (缺) | (缺) | (缺) | (缺) | (缺) | true（hard-coded） | 模块构造选项（默认 true） | `null`（不写 description 字段） |

---

## 7. 修改 `@ToolParam` 行为 — 检查清单

| 想做什么 | 改哪里 |
|---|---|
| 加新的优先级 | `AbstractSpringAiSchemaModule.checkRequired()` 第 97 行 |
| 改方法参数 description/required | `JsonSchemaGenerator.isMethodParameterRequired()` 第 232 行 / `getMethodParameterDescription()` 第 270 行 |
| 改 Record 增强默认值 | `ToolInputSchemaAugmenter.toAugmentedArgumentTypes()` 第 67-68 行 |
| 加新注解属性 | `ToolParam.java` + 上述三处读取 |
| 改「required 默认值」 | 方法参数路径硬编码；字段级路径调模块构造选项 |
| MCP 同步 | `McpSpringAiSchemaModule` + `McpJsonSchemaGenerator`（平行实现） |

---

## 8. 哪里「不」读 `@ToolParam`

容易混淆的几个：

- **`ToolUtils.java`** — 只读 `@Tool` 上的 `name`、`description`、`returnDirect`、`resultConverter`。
  完全不碰 `@ToolParam`。
- **`ToolDefinitions.java`** — 自己也不读，调 `JsonSchemaGenerator.generateForMethodInput` 让它读。
- **`MethodToolCallback` / `MethodToolCallbackProvider`** — 只负责发现方法、构造 callback，
  不读 `@ToolParam`。
- **`FunctionToolCallback`** — 用 `generateForType(Input.class)`，走 victools 路径，
  `@ToolParam` 由 `SpringAiSchemaModule` 读，但只在用户用 `@ToolParam` 标注在 **字段上**
  （比如 record 组件、POJO 字段）时才起作用。`Function<I,O>` 自身的接口参数类型不影响 schema。

---

## 9. 测试样例

### 9.1 流水线 A 测试

文件：`spring-ai-model/src/test/java/org/springframework/ai/util/json/JsonSchemaGeneratorTests.java`

| 测试 | 行号 | 重点 |
|---|---|---|
| `generateSchemaForMethodWithToolParamAnnotations` | 94 | 显式标注，验证 `required` / `description` 都生效 |
| `generateSchemaForMethodWhenParameterRequiredByDefault` | 122 | `@ToolParam` 无参数时 `required=true` |
| `generateSchemaForMethodWithOpenApiSchemaAnnotations` | 150 | `@Schema` 降级 |
| `generateSchemaForMethodWithJacksonAnnotations` | 201 | `@JsonProperty` 降级 |
| `generateSchemaForMethodWithNullableAnnotations` | 229 | `@Nullable` 强制 false |

### 9.2 流水线 B 测试

文件：`spring-ai-model/src/test/java/org/springframework/ai/tool/augment/ToolInputSchemaAugmenterTest.java`

| 测试 | 行号 |
|---|---|
| `RecordWithoutAnnotations` 「no description」默认 | 56 |
| `MixedAnnotationsRecord` 混合 | 59 |
| `toAugmentedArgumentTypes` 全套 | 92-204 |
| integrated flow | 380-412 |

### 9.3 字段级路径

`JsonSchemaGeneratorTests.java` 第 538 行起：`AnnotatedPerson` record 携带
`@ToolParam` 字段，验证 `generateForType(AnnotatedPerson.class)` 的输出。
