# 工具注册方式对比指南

> 本文系统对比 Spring AI 中让一个工具「上线」的四种主要方式，并指出**哪种方式最常用、什么时候用哪种**。

---

## 1. 总览

| # | 方式 | 一句话 | 适用 |
|---|---|---|---|
| 1 | **直接传 POJO**（`.tools(new X())`） | Spring 自动扫描 `@Tool` 方法 | **首选**，最常用 |
| 2 | **`defaultTools(...)`** | 默认工具，每次请求都带上 | 工具稳定、无风险 |
| 3 | **`ToolCallbacks.from(...)`** | 显式转成 `ToolCallback[]` | 要拿数组传给底层 API |
| 4 | **`MethodToolCallbackProvider` Bean** | 暴露为 Spring bean | 跨组件共享 / MCP 暴露 |

四种方式**可以混合**——`.tools(pojo, callback, provider)` 同时接受。

---

## 2. 方式 1：直接传 POJO（最常用）

```java
@Service
class WeatherTools {
    @Tool(description = "查询天气")
    public String getWeather(String city) { ... }
}

ChatClient.create(chatModel)
    .prompt("上海天气？")
    .tools(new WeatherTools())        // ← 直接传对象
    .call()
    .content();
```

**背后**：

1. `ChatClient.tools(Object ...)` 接收异构参数（POJO / `ToolCallback` / `ToolCallbackProvider`）
2. POJO 走 `ToolCallbacks.from(...)` → `MethodToolCallbackProvider.builder().toolObjects(pojo).build()`
3. provider 反射扫描 `@Tool` 方法 → 构造 `MethodToolCallback[]`

**优点**：

- 代码最少，没有模板
- 每次调用前才扫描（有 lazy 优化）
- 适合开发时快速迭代

**限制**：

- 不能拿 callback 数组给第三方 API
- 不能跨请求共享实例（虽然 Spring bean 已经是单例）

---

## 3. 方式 2：默认工具

```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultTools(new WeatherTools(), new OrderTools())
    .build();
```

`defaultTools(...)` 跟 `.tools(...)` 的区别：

| | `.tools(...)` | `.defaultTools(...)` |
|---|---|---|
| 生效范围 | 仅当次请求 | 来自这个 builder 的**每一次**请求 |
| 覆盖关系 | **追加**，不替换 | — |
| 注册时机 | 请求时 | builder 构造时 |
| 适用 | 动态工具、按用户角色变化的工具 | 全局稳定工具 |

⚠️ **危险工具不要放默认**：删除、扣款、改密。任何能造成不可逆影响的工具都应该
按调用传（`.tools(...)`）。

---

## 4. 方式 3：`ToolCallbacks.from(...)`

```java
ToolCallback[] callbacks = ToolCallbacks.from(
    new WeatherTools(),
    new OrderTools()
);

// 场景 A：传给 ChatClient
ChatClient.create(chatModel).prompt("...").tools(callbacks).call();

// 场景 B：直接在 ChatModel 层用
ToolCallingChatOptions opts = ToolCallingChatOptions.builder()
    .toolCallbacks(callbacks)
    .build();
ChatResponse resp = chatModel.call(new Prompt("...", opts));
```

**实现**（`ToolCallbacks.from(Object...)`）：

```java
public static ToolCallback[] from(Object... sources) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(sources)
        .build()
        .getToolCallbacks();
}
```

本质就是方式 1 内部做的事，显式暴露出来。

**什么时候用**：

- 需要给底层 `ChatModel` API（不走 `ChatClient` Advisor）传 callback 数组
- 想手动在多个 `ChatModel` 之间复用同一份 callback
- 测试场景（mock callback 数组）

---

## 5. 方式 4：声明为 Spring Bean

```java
@Configuration(proxyBeanMethods = false)
class ToolConfig {

    @Bean
    ToolCallbackProvider weatherTools(WeatherService svc) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(svc)
            .build();
    }
}
```

`MethodToolCallbackProvider` 实现了 `ToolCallbackProvider`，可以被：

- `ChatClient.tools(...)` 自动识别
- `StaticToolCallbackResolver` 自动收集（用于按名解析）
- 用在 MCP server 暴露场景

**实现**（`MethodToolCallbackProvider`）：

```java
public class MethodToolCallbackProvider implements ToolCallbackProvider {

    private final List<MethodToolCallback> toolCallbacks = new ArrayList<>();

    public MethodToolCallbackProvider(Object... toolObjects) {
        for (Object obj : toolObjects) {
            // 反射 getClass().getDeclaredMethods()
            // 过滤带 @Tool 的方法
            // ToolDefinitions.builder(method) 构造 ToolDefinition
            // 装进 MethodToolCallback
        }
        // 重复 name 检测 → 抛异常
        // 至少一个工具检查 → 否则抛异常
    }
}
```

**优点**：

- 跨组件共享（多次注入同一个 provider）
- 启动期就能验证（重复名字立刻报错）
- 跟 Spring bean 生命周期协同
- 适合 AOT / native-image（让反射元数据生成器扫描 `@Tool` 方法）

**什么时候用**：

- 中大型应用，工具会被多处复用
- 工具要通过 `@McpTool` 暴露给 MCP server
- GraalVM native-image 打包

⚠️ **AOT 注意事项**：

```java
@Tool 工具类必须满足：
- 是 Spring bean（@Component / @Service / @Bean）
- 或者注解 @RegisterReflection(memberCategories = MemberCategory.INVOKE_DECLARED_METHODS)
```

否则 native-image 编译时反射元数据没了，运行时会抛 `IllegalArgumentException: ... not direct method of ...`。

---

## 6. 三种对象的统一接收

`ChatClient.tools(...)` 和 `.defaultTools(...)` 接受异构参数：

```java
ChatClient.create(chatModel).tools(
    new WeatherTools(),        // POJO（方式 1）
    callbackArray,             // ToolCallback[]（方式 3）
    provider,                  // ToolCallbackProvider（方式 4）
    singleCallback             // ToolCallback（裸 callback）
).call();
```

`DefaultChatClient` 内部把以上全部统一成 `ToolCallback[]`，再交给 `ToolCallingAdvisor`。

混用示例：

```java
@Service
class CustomerAssistant {
    private final ChatClient chatClient;

    CustomerAssistant(
        ChatClient.Builder builder,
        CustomerTools customerTools,
        ToolCallback orderLookupCallback,
        ToolCallbackProvider adminToolsProvider
    ) {
        this.chatClient = builder
            .defaultTools(customerTools)            // POJO
            .defaultTools(adminToolsProvider)       // 多个 admin 工具
            .build();
    }

    String answer(String q) {
        return this.chatClient
            .prompt(q)
            .tools(orderLookupCallback)             // 追加单 callback
            .call()
            .content();
    }
}
```

---

## 7. 决策树

```
你要怎么注册工具？
│
├─ 只是普通应用代码 → 方式 1（直接 .tools(new X())）
│      │
│      └─ 工具需要每次都可用 → 方式 2（.defaultTools(...)）
│
├─ 需要 ToolCallback[] 给底层 ChatModel → 方式 3（ToolCallbacks.from(...)）
│
└─ 需要跨 bean 共享 / MCP 暴露 / native-image → 方式 4（@Bean ToolCallbackProvider）
```

---

## 8. 实际选择的建议

| 场景 | 推荐方式 |
|---|---|
| 5 个以内工具，演示 / 小项目 | 方式 1 |
| 全局都能用的安全工具（如查字典） | 方式 2 |
| 想控制请求级别工具集（按用户角色） | 方式 1 |
| 用 `ChatModel` 而不是 `ChatClient` | 方式 3 |
| 中大型项目，工具被多处用 | 方式 4 |
| MCP server 暴露 | 方式 4（必选） |
| GraalVM native-image | 方式 4（+ bean） |

---

## 9. 内部类清单

| 类 | 路径 |
|---|---|
| `ToolCallbackProvider` | `spring-ai-model/.../tool/ToolCallbackProvider.java` |
| `StaticToolCallbackProvider` | `spring-ai-model/.../tool/StaticToolCallbackProvider.java`（默认实现） |
| `MethodToolCallbackProvider` | `spring-ai-model/.../tool/method/MethodToolCallbackProvider.java` |
| `ToolCallbacks` | `spring-ai-model/.../support/ToolCallbacks.java` |

---

## 10. 工具注册涉及的反射点

反射扫描发生在 `MethodToolCallbackProvider` 构造时：

```java
for (Method m : obj.getClass().getDeclaredMethods()) {
    Tool toolAnno = m.getAnnotation(Tool.class);
    if (toolAnno == null) continue;
    // ... 检查返回类型非 Function/Supplier/Consumer
    // ... 收集必要参数信息
    // ... ToolDefinitions.builder(m)  → 触发 @ToolParam 读取
}
```

参数类型解析调用：

- `method.getParameters()` — 读注解（`@ToolParam` 等）
- `method.getGenericParameterTypes()` — 保留泛型信息（详见 [`java-reflection-notes.md`](./java-reflection-notes.md)）

---

## 11. 与 ChatModel 工具调用的关系

> Spring AI 2.0 之后，`ChatClient` 自动注册 `ToolCallingAdvisor` 完成循环。
> 直接用 `ChatModel` 时需要自己驱动循环——见 `spring-ai-docs/.../chatmodel-tool-calling.adoc`（用户视角）
> 和 `docs/tool-system-overview.md` §4（本目录里的链路视图）。

---

## 12. 测试样例

完整可运行示例参见官方：

- `spring-ai-integration-tests/src/test/java/org/springframework/ai/integration/tests/tool/MethodToolCallbackIT.java`
