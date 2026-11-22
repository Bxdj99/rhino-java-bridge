package io.github.rhinobridge

import java.lang.reflect.Proxy

/**
 * 绕过 Rhino 的资源包加载。
 *
 * ## 为什么需要
 * Rhino 的 `ScriptRuntime` 在报错时会去取
 * `ResourceBundle.getBundle("org.mozilla.javascript.resources.Messages")`。
 * 这些 `.properties` 只存在于 jar / APK 的**根目录**，
 * 而在以下场景它们加载不到：
 *
 *  - 被 LSPosed 之类的框架注入到另一个 APK 的进程里（只注入 dex，不注入资源）
 *  - 用 shadow / fat-jar 打包时资源没被合并
 *  - Android 上 classloader 不提供资源访问
 *
 * 一旦找不到，抛 `MissingResourceException`，**脚本连编译都进不去**——
 * 报错信息还是 `Can't find bundle for base name ...`，
 * 非常容易误判成「类找不到」。
 *
 * ## 做法
 * `ScriptRuntime.messageProvider` 是 public static final 字段，
 * 用反射换成自己的实现，直接返回 key，不再碰 ResourceBundle。
 * 替换失败也不致命：只是错误文案变成默认英文。
 */
internal object MessageProviderGuard {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                val runtimeClass = Class.forName("org.mozilla.javascript.ScriptRuntime")
                val field = runtimeClass.getDeclaredField("messageProvider")
                field.isAccessible = true
                val providerClass = Class.forName(
                    "org.mozilla.javascript.ScriptRuntime\$MessageProvider",
                    true,
                    runtimeClass.classLoader,
                )
                val provider = Proxy.newProxyInstance(
                    providerClass.classLoader,
                    arrayOf(providerClass),
                ) { _, method, args ->
                    // getMessage(key, args) → 直接回 key，不查资源包。
                    if (method.name == "getMessage") {
                        (args?.getOrNull(0) as? String) ?: "error"
                    } else {
                        null
                    }
                }
                field.set(null, provider)
                installed = true
            } catch (_: Throwable) {
                // 替换失败不影响功能，只是错误文案不可读。
                installed = true
            }
        }
    }
}
