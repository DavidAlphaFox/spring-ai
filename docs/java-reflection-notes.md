# Java 反射知识点速记

> 本次问答里出现过的几个 Java 反射点解释，供阅读源码时回查。

---

## 1. `method.getGenericParameterTypes()`

### 1.1 签名

```java
public Type[] getGenericParameterTypes()
```

定义在 `java.lang.reflect.Method`（JDK 标准库），返回方法所有**参数**的**泛型类型**，
按声明顺序排列。

### 1.2 返回类型

返回 `java.lang.reflect.Type[]`，**不是** `Class<?>[]`。

`Type` 是顶层接口，下面有五个实现：

| 实现 | 含义 | 例子 |
|---|---|---|
| `Class<?>` | 裸类 | `String.class`、`List.class` |
| `ParameterizedType` | 带类型参数的具体化泛型 | `List<String>`、`Map<String, User>` |
| `GenericArrayType` | 数组元素带泛型 | `List<String>[]`、`T[]` |
| `TypeVariable<?>` | 泛型变量 | `T`（方法签名里的 `<T>`） |
| `WildcardType` | 通配符 | `? extends Number` |

### 1.3 与 `getParameterTypes()` 的区别

```java
public void m(List<String> names, Map<String, User> users, int count);
```

| 方法 | 返回 | 能否看出 `String` / `User` |
|---|---|---|
| `getParameterTypes()` | `Class<?>[] { List.class, Map.class, int.class }` | ❌ 擦除了 |
| `getGenericParameterTypes()` | `Type[] { ParameterizedType{List, String}, ParameterizedType{Map, String, User}, int.class }` | ✅ 保留 |

### 1.4 在 Spring AI 中的用法

文件：`JsonSchemaGenerator.java:147`

```java
for (int i = 0; i < method.getParameterCount(); i++) {
    String name = method.getParameters()[i].getName();
    Type type  = method.getGenericParameterTypes()[i];      // ← 必须用 generic
    ...
    ObjectNode subSchema = generateSchema(subtypeSchemaGenerator, type);
    ...
}
```

**为什么必须用 generic**：

```java
@Tool
List<Book> booksByAuthor(String author);
```

如果用 `getParameterTypes()`，只能得到 `List.class`，JSON Schema 就只能写成：

```json
"booksByAuthor": { "type": "array", "items": {} }    // ← 模型不知道填啥
```

用 `getGenericParameterTypes()` 拿到 `List<Book>`（`ParameterizedType`），
victools 才能递归生成：

```json
"booksByAuthor": {
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "title": { "type": "string" },
      "isbn":  { "type": "string" }
    }
  }
}
```

### 1.5 手动拆 `ParameterizedType`

如果哪天你需要手动取 `List<String>` 里的 `String`：

```java
Type t = method.getGenericParameterTypes()[0];
if (t instanceof ParameterizedType pt) {
    Type raw       = pt.getRawType();           // Class<List>
    Type[] args    = pt.getActualTypeArguments(); // [String]
    String element = args[0].getTypeName();     // "java.lang.String"
}
```

Spring AI 不需要这样写——victools 都帮你展开好了。

---

## 2. `instanceof Class<?>`（Pattern Matching for instanceof）

### 2.1 写法

Java 16+ 引入了 pattern matching：

```java
// 老写法
if (parameterType instanceof Class<?>) {
    Class<?> parameterClass = (Class<?>) parameterType;
    if (ClassUtils.isAssignable(ToolContext.class, parameterClass)) {
        continue;
    }
}

// 新写法（JsonSchemaGenerator 第 148 行）
if (parameterType instanceof Class<?> parameterClass
        && ClassUtils.isAssignable(ToolContext.class, parameterClass)) {
    continue;
}
```

第二行 `parameterClass` 是**自动类型转换**后的变量，作用域为整个 if 表达式体
（含 `&&` 右边的短路部分）。

### 2.2 为什么必须区分 `Class<?>` vs `ParameterizedType`

`getGenericParameterTypes()` 返回的 `Type` 可能是这些形态之一：

| 形态 | `instanceof Class<?>` | 说明 |
|---|---|---|
| `String` | ✅ true | 普通类 |
| `int` | ✅ true | 基本类型也有 Class 对象 |
| `List<String>` | ❌ false | `ParameterizedType` |
| `Map<K, V>` | ❌ false | `ParameterizedType` |
| `List<String>[]` | ❌ false | `GenericArrayType` |

`JsonSchemaGenerator` 第 148 行写 `instanceof Class<?>` 是**有意为之**——
只有**裸类**才做 `ToolContext` 检查。`List<ToolContext>` 不会被误判跳过
（虽然现实中没人这么写）。

### 2.3 `Class<?>` 中的 `?`

```java
List<SomeClass> list;        // SomeClass 的 List
List<?> unknownList;         // 元素类型未知的 List
Class<?> clazz;              // 未知的 Class —— 即「任意 Class」
```

`Class<?>` 等价于 `Class<? extends Object>`，表示「我不知道是什么 Class」。
Spring AI 用 `Class<?>` 而非 `Class`（raw type），是为了**避免 raw type warning**
并明确表示「对具体类型不感兴趣」。

---

## 3. `parameter.getAnnotation(ToolParam.class)`

### 3.1 签名

```java
public <T extends Annotation> T getAnnotation(Class<T> annotationClass)
```

定义在 `java.lang.reflect.AnnotatedElement`（`Parameter`、`Field`、`Method` 都实现了它）。

### 3.2 返回值

- 找到 → 返回注解实例
- 没找到 → 返回 **`null`**（**不抛异常**）
- 同一注解多次出现（很少见） → 不报错，返回第一个

### 3.3 在 Spring AI 中的模式

```java
var tp = parameter.getAnnotation(ToolParam.class);
if (tp != null) return tp.required();                  // 没找到就是 null
```

**总是要做 null 检查**，否则会在缺注解时 NPE。

### 3.4 相关方法

| 方法 | 用途 |
|---|---|
| `getAnnotation(Class)` | 单个注解，找不到返回 null |
| `isAnnotationPresent(Class)` | 是否存在（true/false） |
| `getAnnotations()` | **所有**注解数组 |
| `getDeclaredAnnotations()` | 排除继承的注解 |
| `getAnnotationsByType(Class)` | 同注解多处时返回数组（JDK 8+） |
| `getParameterAnnotations()` (Method 上) | 所有参数的注解数组 |

### 3.5 `@Target` 决定能不能查到这个注解

```java
@Target(ElementType.PARAMETER)        // 只能贴在方法参数上
@Target(ElementType.FIELD)            // 只能贴在字段上
@Target({ PARAMETER, FIELD })         // 两者都行
```

`@ToolParam` 的 `@Target` 是 `{ PARAMETER, FIELD, ANNOTATION_TYPE }`，所以：

- 方法参数：`parameter.getAnnotation(ToolParam.class)` 能读到
- record 组件 / 普通字段：`field.getAnnotation(ToolParam.class)` 能读到
- 类注解上：`getAnnotation` 看不到

---

## 4. `Nullness.forParameter(parameter)`（Spring 工具）

### 4.1 来源

在 Spring AI 项目的 `spring-ai-commons` 里，或者 Spring Framework 6+ 的 `org.springframework.core.Nullness`。
它集中处理来自多个来源的 null 注解判定：

- `javax.annotation.Nullable` (JSR-305)
- `org.springframework.lang.Nullable`
- `jakarta.annotation.Nullable`
- `org.jspecify.annotations.Nullable`
- `com.google.errorprone.annotations.CanIgnoreReturnValue` ...
- Kotlin 内置的 `?` 标记

### 4.2 用法

```java
Nullness n = Nullness.forParameter(parameter);
switch (n) {
    case NULLABLE -> return false;          // 强制非必填
    case NON_NULL -> return true;
    case UNSPECIFIED -> return defaultValue;
}
```

Spring AI 用它：**只要任何一种 `@Nullable` 注解命中，就强制参数为非必填**，覆盖 `@ToolParam` 等其他注解。

### 4.3 注意

返回的可能是 `UNSPECIFIED`（用户啥都没标），这时由 `requiredByDefault` 决定。

---

## 5. `ClassUtils.isAssignable(...)`（Spring 工具）

### 5.1 来源

`org.springframework.util.ClassUtils`

### 5.2 签名

```java
public static boolean isAssignable(Class<?> lhs, Class<?> rhs)
```

判断 `lhs` 是否**可赋值给** `rhs`（包含子类、接口实现）。

### 5.3 在 `JsonSchemaGenerator` 第 149 行的用法

```java
if (parameterType instanceof Class<?> parameterClass
        && ClassUtils.isAssignable(ToolContext.class, parameterClass)) {
    continue;
}
```

判断「这个参数类型是不是 `ToolContext` 或其子类」。

注意参数顺序：`(ToolContext.class, parameterClass)` —— 这里 `ToolContext` 相当于
「目标类型」，`parameterClass` 是「实际参数类型」。如果反过来写，结果仍然是
「可赋值」——`ClassUtils.isAssignable` 是对称的，这点要注意避免误读。

---

## 6. `hasText(String)`（StringUtils）

### 6.1 来源

`org.springframework.util.StringUtils`

### 6.2 签名

```java
public static boolean hasText(@Nullable String str)
```

等价于：`str != null && !str.isEmpty() && containsWhitespace-free characters`。
**注意是「包含非空白字符」**，所以空格字符串 `"   "` 返回 `false`。

Spring AI 用它在判断 `@ToolParam(description = "")`（用户**显式给了空**）和
`@ToolParam`（用户**没写 description**）都算「没有 description」。

```java
if (tp != null && StringUtils.hasText(tp.description())) return tp.description();
//                  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//                  是 StringUtils.hasText，不是 StringUtils.hasLength
```

---

## 7. 反射总结清单

遇到这些场景时怎么查：

| 想做 | 怎么查 |
|---|---|
| 方法参数名 | `method.getParameters()[i].getName()` |
| 方法参数类型（保留泛型） | `method.getGenericParameterTypes()[i]` |
| 方法参数类型（擦除泛型） | `method.getParameterTypes()[i]` |
| 方法参数注解 | `parameter.getAnnotation(X.class)` |
| 是否为某种类型 | `parameterType instanceof Class<?> cls && ClassUtils.isAssignable(X, cls)` |
| 是否 nullable | `Nullness.forParameter(parameter) == NULLABLE` |
| 字段类型（保留泛型） | `field.getGenericType()` |
| 字段注解 | `field.getAnnotation(X.class)` |
| record 组件 | `clazz.getRecordComponents()` |
| record 组件对应字段 | `clazz.getDeclaredField(component.getName())`（会抛 NoSuchFieldException） |

---

## 8. 进一步阅读

- 《Java 核心技术 卷 I》第 8 章 — 反射
- [JDK 反射指南](https://docs.oracle.com/javase/tutorial/reflect/index.html)
- [ParameterizedType JavaDoc](https://docs.oracle.com/javase/8/docs/api/java/lang/reflect/ParameterizedType.html)
- [Spring Framework `ClassUtils` JavaDoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/util/ClassUtils.html)
