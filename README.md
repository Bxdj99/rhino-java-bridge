# rhino-bridge

在 Android 应用进程内，用 JS 脚本直接调 Java / Android 内部 API。

给「想让 AI 现场写脚本去试探一个 App 的内部能力」这种场景用：**不用反编译、不用改代码重编，运行时边试边改。**

---

## 为什么不用反射

直接反射的问题是**每次调用都要在宿主代码里写死类名和方法名**，改一次编译一次、装一次。

脚本化的好处是：AI 在目标进程里现场写、现场跑、看返回值、再调整。等摸索出正确的调用链，再固化进代码。

```kotlin
val bridge = RhinoBridge(classLoader = javaClass.classLoader!!)

val result: String = bridge.eval(
    """
    ({run: function() {
        return {
            brand: javaField("android.os.Build", "BRAND"),
            model: javaField("android.os.Build", "MODEL")
        };
    }})
    """.trimIndent(),
    method = "run",
)
// {"brand":"realme","model":"RMX5200"}
```

脚本约定：**最后一句求值成一个对象**，属性名就是方法名。

---

## 提供的 15 个函数

| 函数 | 用途 |
|---|---|
| `javaCall(cls, method, ...args)` | 调静态方法 |
| `javaNew(cls, [args])` | 构造实例 |
| `javaCallOn(obj, method, [args])` | 调实例方法 |
| `javaField(cls, field)` | 读静态字段 |
| `javaFind(cls, keyword)` | 列类的方法与字段（带类型） |
| `javaPackages(prefix)` | 按前缀找类 |
| `javaLoaders()` | 列出进程内所有 ClassLoader |
| `javaLoad(cls)` | 跨 ClassLoader 加载类 |
| `javaGet(obj, path)` | 按路径读字段，`->` 分段 |
| `javaFindInstances({cls,limit,maxScan})` | 按类型扫堆找实例 |
| `javaListClasses(prefix, keyword)` | 枚举真实类名 |
| `javaProxy(interface, handler)` | 动态创建接口实例（回调参数必备） |
| `javaMethodSignature(cls, method)` | 读方法签名（含参数全限定名） |
| `javaProxyFor(cls, method, argc, ret)` | 按方法名自动造回调代理 |
| `javaSubclass(cls, methods)` | 生成抽象类的子类实例 |

---

## 12 个坑（都是实打实踩出来的）

这一节是本文档最值钱的部分。如果你也在做「脚本化调 Android 内部 API」，大概率会撞上其中几个。

### 1. 不要用 Kotlin 函数对象做桥

```kotlin
// ❌ 错误
scope.put("javaCall", scope, object : BaseFunction() { ... })  // 传 Kotlin lambda 更糟
```

Rhino 会把它包成 `NativeJavaObject`，JS 侧调用时报：

```
TypeError: ft 类型的 JavaScript 值无效
```

**正确做法**：继承 Rhino 的 `BaseFunction`。它不经过 Kotlin↔Java 的函数类型桥。

### 2. `javaToJS` 会让返回值读不出来

```kotlin
// ❌ 返回 Java 对象时
RhinoContext.javaToJS(value, scope)   // JS 侧拿到 NativeJavaObject，读属性全失败
```

JS 侧看到的是 `[object Object]`，`String()` 也拿不到值。

**正确做法**：自己构造普通 JS 对象，把需要的字段提前取出来摊平。

### 3. KKotlin 的 `is Int` 判不出 `java.lang.Integer`

```kotlin
when (value) {
    is Int -> value          // ❌ Java 反射返回的是 java.lang.Integer
    is Number -> value       // ✅ 用 Number 兜住
}
```

不同 Kotlin 版本对基本类型和包装类型的自动判等行为不一致。**用 `Number` / `CharSequence` 这类父类型判断。**

### 4. 集合被转成 JS 数组后，原始对象就丢了

这是最阴的一个：

```js
var list = javaNew("java.util.ArrayList", []);
javaCallOn(list, "add", ["a"]);
javaCallOn(list, "size", []);   // 返回 0，不是 1！
```

原因是 `ArrayList` 是 `Collection`，`toJs` 里走了「转 JS 数组」分支，**原始 Java 对象没有保留**，后续 `javaCallOn` 拿到的是个 JS 数组。

**正确做法**：集合也包装成 JS 对象，把原始引用藏起来（本项目用 `__javaRef` 属性），同时提供 `items` / `size` 只读视图。

### 5. `javaCallOn` 的 receiver 必须还原成原始 Java 对象

```kotlin
val target = RhinoContext.jsToJava(receiver, Any::class.java)  // ❌ 拿到的是包装层
val target = toJava(receiver)                                   // ✅ 取出 __javaRef
```

用 `jsToJava` 的话，反射调实例方法时 receiver 类型不对，全部失败。

### 6. R8/ProGuard 会给 `BaseFunction` 子类改名

Release 包上表现：**函数名还在，一调用就报**：

```
TypeError: msg.undef.method.call
```

因为 R8 把 `call(Context, Scriptable, Scriptable, Object[])` 重命名了，Rhino 按原名反射找不到。

**正确做法**（`proguard-rules.pro`）：

```proguard
-keep class your.package.JavaBridge$* { public *; }
-keepclassmembers class * extends org.mozilla.javascript.BaseFunction {
    public java.lang.Object call(org.mozilla.javascript.Context, org.mozilla.javascript.Scriptable, org.mozilla.javascript.Scriptable, java.lang.Object[]);
    public java.lang.String getFunctionName();
}
```

### 7. Rhino 的资源包在非标准环境加载不到

报错：

```
Can't find bundle for base name org.mozilla.javascript.resources.Messages
```

`Messages.properties` 只在 jar/APK 根目录，如果：
- 被注入到别的 APK 进程（如 LSPosed，只注入 dex 不注入资源）
- 用 shadow / fat-jar 打包漏了资源

就找不到，抛 `MissingResourceException`，**脚本连编译都进不去**。

**正确做法**：反射替换 `ScriptRuntime.messageProvider` 字段（见 `MessageProviderGuard.kt`）。

### 8. ClassLoader 隔离：类在内存里但 `Class.forName` 找不到

动态模块应用（插件化框架）的业务类挂在独立 `PathClassLoader` 上。用默认 loader 报：

```
ClassNotFoundException
```

但类其实已加载。

**正确做法**：从 `ActivityThread.mPackages` 里翻出所有 `LoadedApk.mClassLoader`，逐个试（见 `javaLoaders` / `javaLoad`）。

### 9. 扫堆会卡死主线程

在 UI 线程上做大范围对象图遍历，会触发 ANR 或自己的超时机制，**宿主进程被锁死，要重启应用**。

**正确做法**：纯读取探测类操作放工作线程，给独立超时；只有需要应用自身 Looper 上下文的操作才回主线程。

### 10. 有些操作必须在目标应用的主线程

反过来，**调应用自己的网络层、发消息队列**这类操作，必须在应用主线程（`Looper.getMainLooper()`）执行，否则拿不到登录态、进不了队列，表现是「能构造对象但发不出去」。

**结论：探测走工作线程，业务走主线程。** 要按操作类型分流。

### 11. 进程不对，`ActivityThread.mActivities` 永远是空的

如果代码跑在 `:subprocess` 而不是主进程，`mActivities.size` 恒为 0，读不到任何 Activity。

检查进程名：`currentProcessName == packageName` 才是主进程。

### 12. 混淆后的类名猜不出来

这一点没有技术解法：**只能运行时枚举**。

用 `javaListClasses(prefix, keyword)` 或 `javaFindInstances` 扫，不要试图反编译整个 200MB APK 去猜。

---

## 做不到的事（写清楚，别误导）

| 做不到 | 原因 |
|---|---|
| 直接读任意应用的私有数据 | 你只有该应用自己的权限，没有额外提权 |
| 绕过加固壳拿到真实类名 | 加固会干扰反射，`javaFind` 可能拿到空 |
| 无 Root / 无 Hook 环境注入别的应用 | 需要 LSPosed 这类框架把代码送进去 |
| 全堆无限扫描 | 必须有上限，否则卡死进程 |

---

## 边界与安全

这个库让**代码在目标应用进程内、以该应用自己的身份**执行。

- 能做的 = 该应用自己权限内能做的事，**没有额外提权**
- 权限边界仍由 Android 系统决定
- 仅用于你自己有权测试的应用

---

## 目录

```
bridge/
  JavaBridge.kt            # 15 个桥函数（核心，约 1600 行）
  RhinoBridge.kt           # 独立入口，三行用法
  MessageProviderGuard.kt  # 绕过 Rhino 资源包
demo/
  MainActivity.kt          # 7 个可跑示例
```

## 依赖

```kotlin
implementation("org.mozilla:rhino:1.8.0")
```

`minSdk 24`，不需要 Root 就能跑 demo（demo 只是调自己进程里的 Java API）。

## License

MIT
