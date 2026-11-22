package io.github.rhinobridge

import android.util.Log
import org.json.JSONObject
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable

/**
 * Rhino Java 桥的独立入口。
 *
 * 给「让 AI 现场写脚本去调 Java/Android 内部 API」这件事提供最小可用的一套：
 * 不用反编译、不用改代码重编，运行时试错。
 *
 * ## 三行用法
 * ```kotlin
 * val bridge = RhinoBridge(classLoader = javaClass.classLoader!!)
 * val result = bridge.eval("""({run: function(){ return javaField("android.os.Build","MODEL"); }})""", "run")
 * Log.i("demo", result.toString())   // RMX5200
 * ```
 *
 * ## 脚本约定
 * `eval` 的源码最后一句必须求值成一个对象，属性名即方法名：
 * ```js
 * ({run: function(args){ ... return {...}; }})
 * ```
 * 然后 [eval] 的 method 参数就是 `"run"`。
 *
 * ## 为什么用 Rhino 而不是直接反射
 * 反射的每次调用都要在宿主代码里写死类名方法名，改一次编译一次；
 * Rhino 让脚本在**目标进程内现场编译执行**，AI 可以边试边改，
 * 找到正确的调用链之后再固化。
 */
class RhinoBridge(
    private val classLoader: ClassLoader = RhinoBridge::class.java.classLoader!!,
    /** 优化级别：-1 = 解释执行（启动快、兼容好），>=0 走编译优化。 */
    private val optimizationLevel: Int = -1,
) {

    /** 执行一段脚本并返回 JSON 字符串。 */
    fun eval(source: String, method: String, args: JSONObject = JSONObject()): String {
        val cx = RhinoContext.enter()
        return try {
            cx.optimizationLevel = optimizationLevel
            cx.languageVersion = RhinoContext.VERSION_ES6
            // 关键：Rhino 的 ScriptRuntime 静态初始化会去加载
            // org.mozilla.javascript.resources.Messages（.properties）。
            // 在非标准环境（如被注入到别的 APK 进程）这个资源包常常找不到，
            // 直接抛 MissingResourceException 导致脚本根本编译不了。
            MessageProviderGuard.install()

            val scope: Scriptable = cx.initSafeStandardObjects()
            JavaBridge(classLoader, scope).install()

            val script = cx.compileString(source, "bridge-script", 1, null)
            val exported = script.exec(cx, scope)

            val target = RhinoContext.getUndefinedValue().let {
                org.mozilla.javascript.ScriptableObject.getProperty(
                    exported as Scriptable, method,
                )
            }
            if (target !is org.mozilla.javascript.Function) {
                return errorJson("METHOD_NOT_FOUND", "脚本没有导出方法：$method")
            }
            val result = target.call(cx, scope, exported as Scriptable, arrayOf<Any?>(toJsArg(cx, scope, args)))
            stringify(cx, scope, result)
        } catch (throwable: Throwable) {
            Log.e("RhinoBridge", "eval failed", throwable)
            errorJson("EVAL_FAILED", throwable.message ?: throwable.javaClass.simpleName)
        } finally {
            RhinoContext.exit()
        }
    }

    /** JSONObject → JS 对象。 */
    private fun toJsArg(cx: RhinoContext, scope: Scriptable, args: JSONObject): Any {
        if (args.length() == 0) return cx.newObject(scope)
        return RhinoContext.javaToJS(args.toString(), scope).let {
            // 用 JSON.parse 走一遍，确保得到普通 JS 对象而不是原生引用
            val json = org.mozilla.javascript.ScriptableObject.getProperty(scope, "JSON")
            try {
                val parse = org.mozilla.javascript.ScriptableObject.getProperty(json as Scriptable, "parse")
                (parse as org.mozilla.javascript.Function).call(cx, scope, json as Scriptable, arrayOf<Any?>(args.toString()))
            } catch (t: Throwable) {
                cx.newObject(scope)
            }
        }
    }

    /** JS 值 → JSON 字符串（用脚本自己的 JSON.stringify，避免桥接丢结构）。 */
    private fun stringify(cx: RhinoContext, scope: Scriptable, value: Any?): String {
        val json = org.mozilla.javascript.ScriptableObject.getProperty(scope, "JSON")
        return runCatching {
            val fn = org.mozilla.javascript.ScriptableObject.getProperty(json as Scriptable, "stringify")
            (fn as org.mozilla.javascript.Function).call(cx, scope, json as Scriptable, arrayOf(value))?.toString()
        }.getOrNull() ?: (value?.toString() ?: "null")
    }

    private fun errorJson(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()
}
