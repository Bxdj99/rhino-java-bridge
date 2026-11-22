package io.github.rhinobridge

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * 插件调 Java 的桥（issue #234 的核心价值所在）。
 *
 * 设计要点：**必须是 Rhino 原生 BaseFunction**。
 * 早先把 Kotlin 对象 putProperty 进作用域，Rhino 会把它包成 NativeJavaObject，
 * 反射调用时报「ft 类型的 JavaScript 值无效」；BaseFunction 是引擎自己的类型，
 * 不经过 Kotlin↔Java 的函数类型桥，这才是正路。
 *
 * 对插件暴露三个函数：
 *   javaCall(cls, method, ...args)      调静态方法
 *   javaNew(cls, [...args])             构造实例
 *   javaCallOn(obj, method, [...args])  调实例方法
 *
 * 参数/返回值都做 JS↔Java 双向转换，插件拿到的像是普通 JS 值。
 */
class JavaBridge(
    private val classLoader: ClassLoader,
    private val scope: Scriptable,
) {

    fun install(): Scriptable {
        ScriptableObject.putProperty(scope, "javaCall", StaticCall(classLoader, scope))
        ScriptableObject.putProperty(scope, "javaNew", NewInstance(classLoader, scope))
        ScriptableObject.putProperty(scope, "javaCallOn", InstanceCall(scope))
        ScriptableObject.putProperty(scope, "javaField", FieldRead(classLoader, scope))
        // 自省：列类的方法与字段。Agent 分析 APK 后不用猜签名，先 javaFind 看一眼。
        ScriptableObject.putProperty(scope, "javaFind", FindMembers(classLoader, scope))
        // 类名模糊搜索：只知道包名前缀时用它找候选类。
        ScriptableObject.putProperty(scope, "javaPackages", ListPackages(classLoader, scope))
        // ClassLoader 排查：米家这类用了动态模块的应用，主类不在 base loader 里，
        // 必须把所有已加载的 ClassLoader 找出来逐个试（见 Loaders/CrossLoad）。
        ScriptableObject.putProperty(scope, "javaLoaders", Loaders(scope))
        ScriptableObject.putProperty(scope, "javaLoad", CrossLoad(scope))
        // 暴力读字段：按对象 + 字段名读，支持沿路径下钻，带 visited 去重防递归死循环。
        ScriptableObject.putProperty(scope, "javaGet", DeepGet(scope))
        // 按类型扫堆：给类名，扫进程内所有实例（有界），解决「对象在内存里但不知道路径」。
        ScriptableObject.putProperty(scope, "javaFindInstances", FindInstances(scope))
        // 枚举目标应用真实类名（不反编译）：javaListClasses("com.xiaomi", "rpc")
        ScriptableObject.putProperty(scope, "javaListClasses", ListClasses(classLoader, scope))
        // 动态创建接口实例：Callback / Listener 这类参数必须靠它才能传进去。
        ScriptableObject.putProperty(scope, "javaProxy", ProxyOf(classLoader, scope))
        // 读方法签名（含参数类型的**全限定名**）：javaMethodSignature("类名","方法名")
        // 解决「只知道简名 IResultListener，javaProxy 要全限定名」的死结——
        // 混淆库的回调接口往往在动态加载的 dex 里，类枚举搜不到，只能反射拿。
        ScriptableObject.putProperty(scope, "javaMethodSignature", MethodSignature(classLoader, scope))
        // 按方法名+参数个数自动造回调代理：javaProxyFor("类名","方法名",参数个数,{返回值})
        ScriptableObject.putProperty(scope, "javaProxyFor", ProxyForMethod(classLoader, scope))
        // 生成抽象类的子类实例：javaSubclass("抽象类名", {抽象方法: function})
        // javaProxy 只能代理接口；Parcelable 抽象类（如 IResultListener）必须靠运行时生成子类。
        ScriptableObject.putProperty(scope, "javaSubclass", SubclassOf(classLoader, scope))
        return scope
    }

    // ---------- 类型转换 ----------

    companion object {
        /** JS 值 → Java 值（尽力而为：数字/布尔/字符串/数组）。 */
        /** 包装对象里藏原始 Java 引用的键名。 */
        const val JAVA_REF = "__javaRef"

        /**
         * handle 表：javaFindInstances 扫出来的对象按 h0/h1… 暂存，
         * 供 javaGet({handle:"h0", field:"did"}) 直接取用。
         * 用 WeakHashMap 避免把应用对象长期钉在内存里。
         */
        // 用 ConcurrentHashMap 而不是 WeakHashMap：WeakHashMap 的 expunge 会拿全局锁，
        // 在高频 javaGet 下和 GC 线程互相等，表现就是「一次调用就锁死宿主」。
        // 上限 512 条，超出清空重建，避免无限增长。
        val handleRegistry = java.util.concurrent.ConcurrentHashMap<String, Any>()

        fun toJava(value: Any?): Any? = when (value) {
            null, org.mozilla.javascript.Undefined.instance -> null
            is Scriptable -> {
                // 先看是不是我们自己包的 Java 对象：取出原始引用，别再当 JS 对象转。
                val ref = ScriptableObject.getProperty(value, JAVA_REF)
                if (ref != null && ref !== Scriptable.NOT_FOUND) {
                    ref
                } else if (value is org.mozilla.javascript.NativeArray) {
                    val arr: Scriptable = value
                    val len = (ScriptableObject.getProperty(arr, "length") as? Number)?.toInt() ?: 0
                    (0 until len).map { idx -> toJava(ScriptableObject.getProperty(arr, idx)) }.toTypedArray()
                } else {
                    JSONObject().also { obj ->
                        value.ids.forEach { id ->
                            obj.put(id.toString(), toJava(ScriptableObject.getProperty(value, id.toString())))
                        }
                    }
                }
            }
            is org.mozilla.javascript.NativeArray -> {
                val arr: Scriptable = value
                val len = (org.mozilla.javascript.ScriptableObject.getProperty(arr, "length") as? Number)?.toInt() ?: 0
                (0 until len).map { idx -> toJava(ScriptableObject.getProperty(arr, idx)) }.toTypedArray()
            }
            is org.mozilla.javascript.NativeObject -> {
                val nativeObj: Scriptable = value
                JSONObject().also { obj ->
                    nativeObj.ids.forEach { id ->
                        obj.put(id.toString(), toJava(ScriptableObject.getProperty(nativeObj, id.toString())))
                    }
                }
            }
            // JS 只有一种数字类型。Rhino 经 jsToJava 一律给 java.lang.Double，
            // 目标方法常要 int（getPropertyController(int,int)），直接报
            // IllegalArgumentException: argument has type int, got java.lang.Double。
            // 注意：Rhino 传给 Kotlin 的可能是 Number/NativeNumber 包装，光判 Double 不够，
            // 必须按 Number 统一处理，整数值收敛成 Integer（实测只判 Double 不生效）。
            is Number -> {
                val d = value.toDouble()
                if (d == Math.floor(d) && !d.isInfinite() &&
                    d >= Int.MIN_VALUE.toDouble() && d <= Int.MAX_VALUE.toDouble()) {
                    d.toInt()
                } else {
                    d
                }
            }
            else -> RhinoContext.jsToJava(value, Any::class.java)
        }

        /** Java 返回值 → JS 值。复杂对象统一转成可读描述，避免再次踩桥接坑。 */
        fun toJs(cx: RhinoContext, scope: Scriptable, value: Any?): Any? = when (value) {
            null -> null
            // 注意：Java 反射返回的是包装类型（java.lang.Integer 等），
            // Kotlin 的 `is Int` 对它不一定成立——早先漏判就会掉进 else 分支，
            // 变成 [object Object]（实测 javaCall 返回码读不出来就是这个原因）。
            is Number, is Boolean, is Char, is CharSequence -> value
            is Array<*> -> cx.newArray(scope, value.map { toJs(cx, scope, it) }.toTypedArray())
            is Collection<*> -> {
                // 关键：Java 集合必须**保留原始引用**。
                // 早先直接转成 JS 数组，原始对象就丢了——后续 javaCallOn(o,"add",…)
                // 拿到的其实是 JS 数组，所以 size() 永远是 0（实测就是这个坑）。
                // 现在包成对象：带 JAVA_REF 原始引用 + items 只读视图，读写两便。
                val obj = cx.newObject(scope)
                ScriptableObject.putProperty(obj, JAVA_REF, value)
                ScriptableObject.putProperty(obj, "__class", value.javaClass.name)
                ScriptableObject.putProperty(
                    obj, "items",
                    cx.newArray(scope, value.map { toJs(cx, scope, it) }.toTypedArray()),
                )
                ScriptableObject.putProperty(obj, "size", value.size)
                obj
            }
            is Map<*, *> -> cx.newObject(scope).also { obj ->
                value.forEach { (k, v) ->
                    ScriptableObject.putProperty(obj, k.toString(), toJs(cx, scope, v))
                }
            }
            is JSONObject, is JSONArray -> value.toString()
            // Class / ClassLoader / Method / Field：只留引用，不摊平字段。
            // 摊平这类对象会递归扫出成百上千个成员，既慢又会把原引用埋进包装层，
            // 后续 javaGet(handle,...) 拿到的是包装而不是真对象（实测直接 FIELD_NOT_FOUND）。
            is Class<*>, is ClassLoader, is java.lang.reflect.Method, is java.lang.reflect.Field ->
                cx.newObject(scope).also { obj ->
                    ScriptableObject.putProperty(obj, JAVA_REF, value)
                    ScriptableObject.putProperty(obj, "__ref", value.toString())
                    // P3：补 __class，方便 JS 侧判断类型，也便于调用方确认拿到的是 Class
                    ScriptableObject.putProperty(obj, "__class", value.javaClass.name)
                }
            else -> {
                // 任意 Java 对象：**不要**直接 javaToJS——那会变成 NativeJavaObject，
                // JS 侧读属性/比较数值全失效（实测「读不到 doScene 返回码」就是这个原因）。
                // 包成普通 JS 对象，字段值提前取出来，另附 __class / __str 便于判断类型。
                val obj = cx.newObject(scope)
                // 存住原始 Java 引用的关键：后续 javaCallOn 必须拿到真对象，
                // 否则拿到的是这层包装，所有实例方法调用都会失败（实测 size 恒为 0）。
                ScriptableObject.putProperty(obj, JAVA_REF, value)
                ScriptableObject.putProperty(obj, "__class", value.javaClass.name)
                runCatching { ScriptableObject.putProperty(obj, "__str", value.toString()) }
                // 常见「带返回值」的对象：把 intValue/booleanValue/name/ordinal 直接摊平
                listOf(
                    "intValue" to ::readIntValue,
                    "booleanValue" to ::readBooleanValue,
                ).forEach { (key, reader) ->
                    runCatching { reader(value) }?.let { ScriptableObject.putProperty(obj, key, it) }
                }
                // 反射公开字段：**只对"值对象"做**。
                // 早先无条件展开会把业务对象（DeviceController / PropertyController 等）
                // 也摊平成快照，JS 侧再调它的方法时拿到的是快照而不是活对象，
                // 报「找不到方法 org.json.JSONObject.getValue」——方法全打偏了。
                if (isPlainValueObject(value)) {
                    runCatching {
                        value.javaClass.fields.forEach { field ->
                            runCatching {
                                field.isAccessible = true
                                ScriptableObject.putProperty(obj, field.name, toJs(cx, scope, field.get(value)))
                            }
                        }
                    }
                }
                obj
            }
        }

        /**
         * 只有"纯值对象"才值得展开字段。
         *
         * 判断标准：位于 java.* / android.* / kotlin.* 且不是控制器/管理器这类有行为的长生命周期对象。
         * 业务对象（*Controller / *Manager / *Api / *Helper / *Service）一律不展开，
         * 只保留 __javaRef，保证后续还能拿它调方法。
         */
        fun isPlainValueObject(value: Any): Boolean {
            val name = value.javaClass.name
            val behaviorLike = name.endsWith("Controller") || name.endsWith("Manager") ||
                name.endsWith("Api") || name.endsWith("API") || name.endsWith("Helper") ||
                name.endsWith("Service") || name.endsWith("Client") || name.endsWith("Impl")
            if (behaviorLike) return false
            return name.startsWith("java.") || name.startsWith("android.") || name.startsWith("kotlin.")
        }

        /**
         * 跨 loader 找类：先上下文 loader，再所有已知 loader。
         * 动态模块应用的类只有特定 loader 认得，单一 loader 会漏。
         */
        /**
         * 系统/框架类的作用域前缀。
         *
         * 为什么必须单独处理：目标应用里可能有**重打包的同名类**（或 ROM 自带影子类），
         * 一旦把 android.net.Uri 这类系统类解析到应用的 loader 上，会得到一个
         * 非系统的 Uri，后续 query/call 时类型不匹配、甚至把插件宿主 socket 打挂
         * （实测 javaCall("android.net.Uri","parse",str) 每次都使宿主断连）。
         */
        private val SYSTEM_PREFIXES = listOf(
            "java.", "javax.", "android.", "androidx.", "kotlin.", "kotlinx.",
            "dalvik.", "sun.", "com.android.internal.",
        )

        fun isSystemClass(name: String): Boolean = SYSTEM_PREFIXES.any { name.startsWith(it) }

        fun resolveClass(name: String): Class<*>? {
            // 系统类只走 bootclasspath / 宿主 loader，绝不委派给目标应用的 ClassLoader。
            if (isSystemClass(name)) {
                // 只认两处：bootclasspath、宿主模块自己的 loader。
                // 第三方方还试 Class.forName(name)，但**必须**确保拿到的是系统那份：
                // 目标应用 loader 里若有影子 android.net.Uri，静态初始化会触发它自己的
                // ContentProvider 解析 → 在插件工作线程上死锁（实测 parse 一调就卡死执行器）。
                runCatching {
                    val c = Class.forName(name)
                    if (c.classLoader == null || c.classLoader === ClassLoader.getSystemClassLoader()) return c
                }
                runCatching { return Class.forName(name, false, JavaBridge::class.java.classLoader) }
                runCatching { return Class.forName(name) }
            }
            val loaders = mutableListOf<ClassLoader>()
            Thread.currentThread().contextClassLoader?.let(loaders::add)
            runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null)
                    ?.let { (it as? android.content.Context)?.classLoader?.let(loaders::add) }
            }
            runCatching {
                val at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null)
                val f = at!!.javaClass.getDeclaredField("mPackages").apply { isAccessible = true }
                (f.get(at) as? Map<*, *>)?.values?.forEach { ref ->
                    runCatching {
                        val wr = ref as? java.lang.ref.WeakReference<*>
                        val apk = wr?.get() ?: ref
                        (apk?.javaClass?.getMethod("getClassLoader")?.invoke(apk) as? ClassLoader)
                            ?.let(loaders::add)
                    }
                }
            }
            for (loader in loaders.distinct()) {
                var cur: ClassLoader? = loader
                while (cur != null) {
                    runCatching { return Class.forName(name, false, cur) }
                    cur = cur.parent
                }
            }
            return null
        }

        /** 读 intValue()：Java 的 Integer/枚举码值常见入口。 */
        fun readIntValue(value: Any): Int? = runCatching {
            value.javaClass.getMethod("intValue").invoke(value) as? Int
        }.getOrNull()

        /** 读 booleanValue()。 */
        fun readBooleanValue(value: Any): Boolean? = runCatching {
            value.javaClass.getMethod("booleanValue").invoke(value) as? Boolean
        }.getOrNull()

        /** 把失败信息作为 {__javaError:"..."} 抛回 JS（插件可 try/catch）。 */
        fun throwOrReturn(cx: RhinoContext, message: String): Any? =
            cx.evaluateString(
                cx.initStandardObjects(),
                "({__javaError: ${JSONObject.quote(message)}})",
                "java-error", 1, null,
            )

        fun fail(cx: RhinoContext, message: String): Any? = throwOrReturn(cx, message)

        /** 展开反射包装，把真实异常信息给插件看。 */
        fun describe(throwable: Throwable): String {
            val cause = (throwable as? java.lang.reflect.InvocationTargetException)?.targetException ?: throwable
            return "${cause.javaClass.simpleName}: ${cause.message?.take(300)}"
        }

        /** 按参数个数/类型找匹配的方法（重载友好）。 */
        fun findMethod(
            target: Class<*>,
            name: String,
            argCount: Int,
        ): java.lang.reflect.Method? = target.methods
            .filter { it.name == name && it.parameterCount == argCount }
            .minByOrNull { method ->
                // 优先选参数类型更宽松的（Object 优先），减少基本类型不匹配
                method.parameterTypes.count { it == Any::class.java }
            }

        /**
         * 按**真实参数类型**选重载。
         *
         * 为什么必须有：只按参数个数挑会静默选错重载——
         * JSONObject.put(String,Object) 与 put(String,int) 个数相同，选错那次
         * 调用既不报错也不写入（实测 payload 一直是 {}）；
         * setDeviceProps(String,JSONObject,Callback) 也会被选成别的三参重载。
         * 这里按「参数能否接受该实参」逐条打分，命中唯一最佳者；全不匹配时退回第一个。
         */
        fun findMethodForArgs(
            target: Class<*>,
            name: String,
            javaArgs: Array<Any?>,
        ): java.lang.reflect.Method? {
            val candidates = target.methods.filter { it.name == name && it.parameterCount == javaArgs.size }
            if (candidates.isEmpty()) return null
            if (candidates.size == 1) return candidates.first()
            var best: java.lang.reflect.Method? = null
            var bestScore = -1
            for (method in candidates) {
                var score = 0
                var usable = true
                method.parameterTypes.forEachIndexed { index, type ->
                    val arg = javaArgs[index]
                    if (arg == null) {
                        // null 只对非基本类型合法；给基本类类型扣重分
                        if (type.isPrimitive) { score -= 10 }
                    } else {
                        val boxed = boxed(type)
                        when {
                            boxed.isInstance(arg) -> score += 3
                            // 数字放宽：int 实参可以喂 long/double 形参
                            arg is Number && (boxed == java.lang.Long::class.java ||
                                boxed == java.lang.Double::class.java ||
                                boxed == java.lang.Float::class.java) -> score += 2
                            else -> usable = false
                        }
                    }
                }
                if (!usable) continue
                if (score > bestScore) {
                    bestScore = score
                    best = method
                }
            }
            return best ?: candidates.first()
        }

        private fun boxed(type: Class<*>): Class<*> = when (type) {
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            else -> type
        }
    }

    private class StaticCall(
        private val classLoader: ClassLoader,
        private val scope: Scriptable,
    ) : BaseFunction() {
        override fun getFunctionName(): String = "javaCall"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            val className = args.getOrNull(0)?.toString() ?: return JavaBridge.fail(cx, "javaCall 需要类名")
            val methodName = args.getOrNull(1)?.toString() ?: return JavaBridge.fail(cx, "javaCall 需要方法名")
            val rest = args.drop(2)
            return try {
                // 必须走 resolveClass：宿主的 classLoader 是 io.github.mangi.eta 的，
                // 用它 ForName 目标应用的类会直接 ClassNotFoundException
                // （实测 javaCall("java.lang.Class","forName","com.xiaomi...") 全挂）。
                val target = resolveClass(className)
                    ?: Class.forName(className, true, classLoader)
                // P2：单参数时如果调用方传了 JS 数组，应当**展开**成 N 个参数，
                // 而不是当成一个 Object[]（实测 javaCall("android.net.Uri","parse",
                // ["content://x/y"]) 报 argument has type String, got Object[]）。
                val flatArgs = if (rest.size == 1 && rest[0] is org.mozilla.javascript.NativeArray) {
                    val arr = rest[0] as org.mozilla.javascript.NativeArray
                    val len = arr.length.toInt()
                    (0 until len).map { idx -> ScriptableObject.getProperty(arr, idx as Int) }
                } else {
                    rest
                }
                val javaArgs = flatArgs.map { toJava(it) }.toTypedArray()
                val method = findMethodForArgs(target, methodName, javaArgs)
                    ?: findMethod(target, methodName, javaArgs.size)
                    ?: return JavaBridge.throwOrReturn(cx, "找不到方法 $className.$methodName/${rest.size}")
                method.isAccessible = true
                toJs(cx, scope, method.invoke(null, *javaArgs))
            } catch (throwable: Throwable) {
                JavaBridge.throwOrReturn(cx, JavaBridge.describe(throwable))
            }
        }
    }

    private class NewInstance(
        private val classLoader: ClassLoader,
        private val scope: Scriptable,
    ) : BaseFunction() {
        override fun getFunctionName(): String = "javaNew"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            val className = args.getOrNull(0)?.toString() ?: return JavaBridge.fail(cx, "javaNew 需要类名")
            val ctorArgs = when (val raw = args.getOrNull(1)) {
                null, org.mozilla.javascript.Undefined.instance -> emptyList()
                is org.mozilla.javascript.NativeArray -> {
                    val arr: Scriptable = raw
                    val len = (ScriptableObject.getProperty(arr, "length") as? Number)?.toInt() ?: 0
                    (0 until len).map { idx -> ScriptableObject.getProperty(arr, idx) }
                }
                else -> listOf(raw)
            }
            return try {
                val target = resolveClass(className) ?: Class.forName(className, true, classLoader)
                val ctor = target.constructors
                    .filter { it.parameterCount == ctorArgs.size }
                    .minByOrNull { it.parameterTypes.count { p -> p == Any::class.java } }
                    ?: return JavaBridge.throwOrReturn(cx, "找不到构造器 $className/${ctorArgs.size}")
                ctor.isAccessible = true
                val javaArgs = ctorArgs.map { toJava(it) }.toTypedArray()
                toJs(cx, scope, ctor.newInstance(*javaArgs))
            } catch (throwable: Throwable) {
                JavaBridge.throwOrReturn(cx, JavaBridge.describe(throwable))
            }
        }
    }

    /** 列类成员：javaFind("com.xxx.Foo") → {methods:[...], fields:[...]}。 */
    private class FindMembers(
        private val classLoader: ClassLoader,
        private val scope: Scriptable,
    ) : BaseFunction() {
        override fun getFunctionName(): String = "javaFind"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            val className = args.getOrNull(0)?.toString() ?: return JavaBridge.fail(cx, "javaFind 需要类名")
            val keyword = args.getOrNull(1)?.toString()?.lowercase()
            return try {
                val target = resolveClass(className) ?: Class.forName(className, true, classLoader)
                // 直接回 JSON 字符串：cx.newObject + putProperty 的 JS 对象
                // 在 JSON.stringify 时不稳（实测只回 {"ok":true}），字符串最可靠。
                val methods = target.methods
                    .filter { keyword == null || it.name.lowercase().contains(keyword) }
                    .take(200)
                    .map { m ->
                        m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + "):" +
                            m.returnType.simpleName
                    }
                val fields = target.fields
                    .filter { keyword == null || it.name.lowercase().contains(keyword) }
                    .take(200)
                    .map { f -> f.name + ":" + f.type.simpleName }
                // 回真正的 JS 字符串（不要用 JSONObject.toString 后被当对象包一层）
                val payload = JSONObject()
                    .put("class", target.name)
                    .put("methods", JSONArray(methods))
                    .put("fields", JSONArray(fields))
                    .toString()
                org.mozilla.javascript.Context.javaToJS(payload, scope)
            } catch (throwable: Throwable) {
                JavaBridge.throwOrReturn(cx, JavaBridge.describe(throwable))
            }
        }
    }

    /**
     * 按包名列出可见类（只列已加载 classpath 里的）。
     * 简化实现：从已加载的 DexFile 里读类名，够 Agent 定位目标类。
     */
    /**
     * 按路径暴力读字段：javaGet(obj, "a.b.c")。
     *
     * 为什么需要：混淆后的对象图深浅不定，模型没法预知路径。
     * 这里沿字段名一路 getField/getDeclaredField 读下去，
     * 中途遇到 null 就返回 null，不抛异常；配合 findInstances 能挖到藏很深的对象。
     */
    private class DeepGet(private val scope: Scriptable) : BaseFunction() {
        override fun getFunctionName(): String = "javaGet"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            // 两种调用方式：
            // 1) javaGet(obj, "a.b.c")
            // 2) javaGet({handle:"h0", field:"did"})  // handle 来自 javaFindInstances
            val raw0 = args.getOrNull(0)
            // 关键技术细节：不能先对整个 options 调 toJava。
            // toJava 对「对象里套 NativeArray」的组合会抛 msg.invalid.type
            // （实测 {handle, fields:[...]} 直接炸）。所以先把 fields 从原始
            // Scriptable 里抠出来，再让 toJava 处理剩下的标量字段。
            var rawBatch: List<String> = emptyList()
            run {
                val src = raw0 as? Scriptable ?: return@run
                val arr = ScriptableObject.getProperty(src, "fields")
                if (arr != null && arr !== Scriptable.NOT_FOUND && arr is Scriptable) {
                    val len = (ScriptableObject.getProperty(arr, "length") as? Number)?.toInt() ?: 0
                    rawBatch = (0 until len).mapNotNull { idx ->
                        ScriptableObject.getProperty(arr, idx)?.toString()?.takeIf(String::isNotBlank)
                    }
                }
            }
            val opts = toJava(raw0)
            val target: Any
            val path: String
            // 批量读取：一次调用返回多个字段，避免每个字段一次跨进程往返。
            // 这是性能关键路径：单字段调用每次都要宿主排期调度，连读 5 个就撞超时。
            var batch: List<String> = emptyList()
            if (opts is JSONObject && (opts.has("handle") || opts.has("field") || rawBatch.isNotEmpty())) {
                val handleKey = opts.optString("handle")
                val fromHandle = if (handleKey.isNotBlank()) handleRegistry[handleKey] else null
                target = fromHandle
                    ?: toJava(opts.opt("obj"))
                    ?: return JavaBridge.fail(cx, "handle 不存在或已失效：$handleKey")
                batch = rawBatch
                path = opts.optString("field").ifBlank { opts.optString("path") }
                // {handle, self:true} → 直接把这个对象的引用交出去，
                // 供 javaCallOn 对 handle 调方法用（handle 本身只是字符串，不能当 receiver）。
                if (path.isBlank() && batch.isEmpty() && opts.optBoolean("self", false)) {
                    return toJs(cx, scope, target)
                }
            } else {
                target = toJava(raw0) ?: return JavaBridge.fail(cx, "javaGet 需要对象")
                path = args.getOrNull(1)?.toString().orEmpty()
            }
            if (path.isBlank() && batch.isEmpty()) return JavaBridge.fail(cx, "javaGet 需要字段路径或 field 名")
            // 批量分支：一次过一遍字段表，返回 {ok, values:{...}, missing:[...]}。
            // 为什么放成一次调用：宿主侧一次 invoke 只排期一次，字段多也不会叠超时。
            if (batch.isNotEmpty()) {
                val values = JSONObject()
                val missing = JSONArray()
                for (fieldName in batch) {
                    val segments = if (fieldName.contains("->")) fieldName.split("->") else fieldName.split('.')
                    var current: Any? = target
                    var lost = false
                    for (segment in segments) {
                        if (current == null) {
                            lost = true
                            break
                        }
                        current = readField(current, segment)
                        if (current === MISSING) {
                            lost = true
                            break
                        }
                    }
                    if (lost) missing.put(fieldName) else values.put(fieldName, toPlainJson(current))
                }
                return JSONObject()
                    .put("ok", true)
                    .put("values", values)
                    .put("missing", missing)
                    .put("count", values.length())
            }
            // 分隔符兼容：优先用 '->'（避开包名/类名里的点），没有才退回 '.'。
            val segments = if (path.contains("->")) path.split("->") else path.split('.')
            var current: Any? = target
            for (segment in segments) {
                if (current == null) return null
                current = readField(current, segment)
                if (current == MISSING) {
                    return JSONObject()
                        .put("ok", false)
                        .put("code", "FIELD_NOT_FOUND")
                        .put("field", segment)
                        .put("onClass", target.javaClass.name)
                }
            }
            return toJs(cx, scope, current)
        }

        /** 批量读取用：把字段值压成能塞进 JSONObject 的形态，复杂对象只留类型名。 */
        private fun toPlainJson(value: Any?): Any = when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is Boolean, is Number, is String -> value
            is Char -> value.toString()
            is CharSequence -> value.toString()
            is Array<*> -> JSONArray(value.map { toPlainJson(it) })
            is Collection<*> -> JSONArray(value.map { toPlainJson(it) })
            is Map<*, *> -> JSONObject().also { obj ->
                value.forEach { (k, v) -> obj.put(k.toString(), toPlainJson(v)) }
            }
            else -> "${value.javaClass.name}@${Integer.toHexString(System.identityHashCode(value))}"
        }

        /** 读一层字段：先公共字段，再沿父类找 declared 字段。 */
        private fun readField(source: Any, name: String): Any? {
            for (field in source.javaClass.fields) {
                if (field.name == name) {
                    return runCatching {
                        field.isAccessible = true
                        field.get(source)
                    }.getOrNull()
                }
            }
            var cls: Class<*>? = source.javaClass
            while (cls != null) {
                for (field in cls.declaredFields) {
                    if (field.name == name) {
                        return runCatching {
                            field.isAccessible = true
                            field.get(source)
                        }.getOrNull()
                    }
                }
                cls = cls.superclass
            }
            return MISSING
        }

        private companion object {
            val MISSING = Any()
        }
    }

    /**
     * 按类型扫堆：javaFindInstances("com.x.Device", 20)。
     *
     * 实现方式：从 ActivityThread 里的几个「根」（mInitialApplication、mPackages 里的
     * LoadedApk、当前 Activity、已注册的 ContentProvider）出发做**有界**对象图遍历，
     * 命中匹配类型就收。深度/节点数都有上限，避免把进程扫死。
     * 这是「不知道对象藏在哪」时的终极手段。
     */
    private class FindInstances(private val scope: Scriptable) : BaseFunction() {
        override fun getFunctionName(): String = "javaFindInstances"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            // 参数两种形式：javaFindInstances("类名", limit, maxNodes) 或
            // javaFindInstances({cls:"类名", limit:20, maxNodes:20000})——后者是用户反馈要的签名。
            val raw0 = args.getOrNull(0)
            val opts = toJava(raw0)
            val typeName = when {
                opts is JSONObject && opts.has("cls") -> opts.optString("cls")
                else -> raw0?.toString().orEmpty()
            }
            if (typeName.isBlank()) return JavaBridge.fail(cx, "javaFindInstances 需要类名（或 {cls:...}）")
            val num = { key: String, fallback: Int ->
                when {
                    opts is JSONObject && opts.has(key) -> opts.optInt(key, fallback)
                    else -> fallback
                }
            }
            val limit = num("limit", 20).coerceIn(1, 200)
            // 上限大幅放宽：实测 16700 个对象时目标类 0 命中，说明真机堆远大于此。
            // maxScan 是用户可调的主要旋钮。
            val maxNodes = maxOf(
                num("maxScan", 400_000),
                num("maxNodes", 400_000),
            ).coerceIn(1_000, 5_000_000)
            val hits = mutableListOf<Any>()
            val visited = java.util.IdentityHashMap<Any, Boolean>()
            var scanned = 0
            val queue = ArrayDeque<Any>()
            // 位置参数形式时，从 args[1]/args[2] 兜底取 limit/maxNodes
            val pLimit = ((args.getOrNull(1) as? Number)?.toInt() ?: limit).coerceIn(1, 200)
            val pMax = ((args.getOrNull(2) as? Number)?.toInt() ?: maxNodes).coerceIn(1_000, 5_000_000)
            val limit2 = if (opts is JSONObject) limit else pLimit
            val maxNodes2 = if (opts is JSONObject) maxNodes else pMax

            // 第一优先：android.os.Debug.getInstances —— 这是真正的全堆枚举，
            // 不经过对象图遍历，不怕对象藏多深。需要目标进程可调试（debuggable），
            // 失败时静默退回到对象图扫描。
            runCatching {
                val target = resolveClass(typeName)
                if (target != null) {
                    val debugCls = Class.forName("android.os.Debug")
                    val method = debugCls.getMethod(
                        "getInstances", Class::class.java, Long::class.javaPrimitiveType
                    )
                    val instances = method.invoke(null, target, 0L) as? Array<*>
                    instances?.take(limit2 * 4)?.forEach { inst ->
                        if (inst != null) hits += inst
                    }
                }
            }
            // 第一优先：先把目标类自己的 static 字段塞进队列。
            // 实测 o00oO0o 这类单例挂在静态字段上，只走对象图遍历会漏掉。
            runCatching {
                val target = resolveClass(typeName) ?: return@runCatching
                target.declaredFields
                    .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .forEach { field ->
                        runCatching {
                            field.isAccessible = true
                            field.get(null)?.let(queue::addLast)
                        }
                    }
            }
            // 根：当前 Activity、Application、以及各 LoadedApk
            runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null)
                    ?.let(queue::addLast)
            }
            runCatching {
                val at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null) ?: return@runCatching
                val f = at.javaClass.getDeclaredField("mPackages").apply { isAccessible = true }
                (f.get(at) as? Map<*, *>)?.values?.forEach { ref ->
                    val wr = ref as? java.lang.ref.WeakReference<*>
                    (wr?.get() ?: ref)?.let(queue::addLast)
                }
            }

            while (queue.isNotEmpty() && scanned < maxNodes2 && hits.size < limit2) {
                val node = queue.removeFirst()
                if (node == null) continue
                if (visited.put(node, true) != null) continue
                scanned++
                val cls = node.javaClass
                // 命中判断：按类名或父类名匹配
                var c: Class<*>? = cls
                var matched = false
                while (c != null) {
                    if (c.name == typeName) { matched = true; break }
                    c = c.superclass
                }
                if (matched && node.javaClass.name != "java.lang.Class") hits += node
                // 集合/Map：直接展开元素，不深入它内部的 Node/Entry 实现
                runCatching {
                    if (node is Collection<*>) {
                        node.take(2_000).forEach { item -> if (item != null) queue.addLast(item) }
                    } else if (node is Map<*, *>) {
                        node.entries.take(2_000).forEach { entry ->
                            entry.value?.let(queue::addLast)
                        }
                    }
                }
                // 展开字段（跳过明显的叶子类型，减少无谓扫描）
                if (isLeaf(cls)) continue
                var cc: Class<*>? = cls
                while (cc != null) {
                    for (field in cc.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                        if (field.type.isPrimitive) continue
                        runCatching {
                            field.isAccessible = true
                            field.get(node)?.let(queue::addLast)
                        }
                    }
                    cc = cc.superclass
                }
            }

            // 返回值把**原始 Java 对象**一起带出去：JS 侧拿到的是
            // { class, str, handle } 的数组；handle 里藏原始引用（toJava 会还原），
            // 可以直接喂给 javaGet / javaCallOn。
            val arr = hits.map { hit ->
                val holder = cx.newArray(scope, arrayOf<Any?>(hit))
                JSONObject()
                    .put("class", hit.javaClass.name)
                    .put("str", runCatching { hit.toString() }.getOrNull()?.take(120))
                    .put("handle", holder.toString())
            }
            // 用 WeakHashMap 在宿主对象和 handle 字符串间建立映射，供 javaGet 使用。
            if (handleRegistry.size > 512) handleRegistry.clear()
            hits.forEachIndexed { i, hit -> handleRegistry["h$i"] = hit }
            return JSONObject()
                .put("ok", true)
                .put("scanned", scanned)
                .put("hits", JSONArray(arr))
                .put("note", "handle 形如 h0/h1…，配合 javaGet({handle,field}) 使用")
        }

        /** 这些类型不展开：展开只会浪费时间且没有业务对象。 */
        /**
         * 是否「不展开」。
         *
         * 关键修正：java.util 的集合**必须展开元素**——设备对象就躺在
         * ArrayList/Map 里，早先整片跳过 java.util.*，导致扫描规模卡在 1.7 万、
         * Device 永远 0 命中（实测）。现在只跳过真正的叶子和集合内部实现。
         */
        private fun isLeaf(cls: Class<*>): Boolean {
            val n = cls.name
            if (cls.isArray) return false                 // 数组要展开元素
            if (Collection::class.java.isAssignableFrom(cls)) return false  // 列表/集合要展开
            if (Map::class.java.isAssignableFrom(cls)) return false         // Map 要展开
            return n == "java.lang.String" || n == "java.lang.Class" ||
                n.startsWith("java.lang.Integer") || n.startsWith("java.lang.Long") ||
                n.contains("StringBuilder") || n.contains("Paint")
        }
    }

    /**
     * 列出进程里所有已加载的 ClassLoader。
     *
     * 为什么需要：动态模块（RePlugin/Tinker 一类）把自己的类挂在**独立的**
     * PathClassLoader 上。用 base classloader 去 Class.forName 会 ClassNotFound，
     * 但类其实在内存里——只是 loader 不对。
     */
    private class Loaders(private val scope: Scriptable) : BaseFunction() {
        override fun getFunctionName(): String = "javaLoaders"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            val out = cx.newObject(scope)
            val collected = mutableListOf<Any?>()
            var index = 0
            // 已知的几个入口：ActivityThread、当前的 context 的 loader、以及它们的 parent 链。
            val seeds = mutableListOf<ClassLoader>()
            runCatching {
                val at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null)
                (at?.javaClass?.getMethod("getSystemContext")?.invoke(at) as? android.content.Context)
                    ?.classLoader?.let(seeds::add)
            }
            runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null)
                    ?.let { app -> (app as? android.content.Context)?.classLoader?.let(seeds::add) }
            }
            // 已加载的 PathClassLoader 实例：从 ActivityThread 的 mPackages 里翻。
            runCatching {
                val at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null)
                val field = at!!.javaClass.getDeclaredField("mPackages").apply { isAccessible = true }
                val map = field.get(at) as? Map<*, *>
                map?.values?.forEach { ref ->
                    runCatching {
                        val wr = ref as? java.lang.ref.WeakReference<*>
                        val loadedApk = wr?.get() ?: ref
                        val cl = loadedApk?.javaClass?.getMethod("getClassLoader")?.invoke(loadedApk)
                            as? ClassLoader
                        cl?.let(seeds::add)
                    }
                }
            }
            val seen = mutableSetOf<ClassLoader>()
            seeds.forEach { seed ->
                var current: ClassLoader? = seed
                var depth = 0
                while (current != null && depth < 8) {
                    if (seen.add(current)) {
                        val info = cx.newObject(scope)
                        ScriptableObject.putProperty(info, "class", current.javaClass.name)
                        ScriptableObject.putProperty(info, "index", index)
                        ScriptableObject.putProperty(info, "toString", current.toString().take(200))
                        collected += info
                        index++
                    }
                    current = current.parent
                    depth++
                }
            }
            ScriptableObject.putProperty(out, "count", index)
            ScriptableObject.putProperty(out, "list", cx.newArray(scope, collected.toTypedArray()))
            return out
        }
    }

    /**
     * 跨 ClassLoader 加载类：javaLoad(类名)。
     * 依次试所有已知 loader，返回第一个成功的与它的 loader 描述。
     */
    private class CrossLoad(private val scope: Scriptable) : BaseFunction() {
        override fun getFunctionName(): String = "javaLoad"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            val name = args.getOrNull(0)?.toString() ?: return JavaBridge.fail(cx, "javaLoad 需要类名")
            val loaders = mutableListOf<ClassLoader>()
            runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null)
                    ?.let { (it as? android.content.Context)?.classLoader?.let(loaders::add) }
            }
            runCatching {
                val at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null)
                val field = at!!.javaClass.getDeclaredField("mPackages").apply { isAccessible = true }
                (field.get(at) as? Map<*, *>)?.values?.forEach { ref ->
                    runCatching {
                        val wr = ref as? java.lang.ref.WeakReference<*>
                        val loadedApk = wr?.get() ?: ref
                        (loadedApk?.javaClass?.getMethod("getClassLoader")?.invoke(loadedApk)
                            as? ClassLoader)?.let(loaders::add)
                    }
                }
            }
            // 也把当前线程上下文的 loader 带上
            Thread.currentThread().contextClassLoader?.let(loaders::add)
            val out = cx.newObject(scope)
            val triedList = mutableListOf<Any?>()
            for (loader in loaders.distinct()) {
                var current: ClassLoader? = loader
                while (current != null) {
                    runCatching {
                        val cls = Class.forName(name, false, current)
                        ScriptableObject.putProperty(out, "ok", true)
                        ScriptableObject.putProperty(out, "class", cls.name)
                        ScriptableObject.putProperty(out, "loader", current.toString().take(200))
                        return out
                    }
                    current = current.parent
                }
                triedList += loader.toString().take(120)
            }
            ScriptableObject.putProperty(out, "ok", false)
            ScriptableObject.putProperty(out, "code", "CLASS_NOT_FOUND_ANY_LOADER")
            ScriptableObject.putProperty(out, "tried", cx.newArray(scope, triedList.toTypedArray()))
            return out
        }
    }

    private class ListPackages(
        private val classLoader: ClassLoader,
        private val scope: Scriptable,
    ) : BaseFunction() {
        override fun getFunctionName(): String = "javaPackages"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            val prefix = args.getOrNull(0)?.toString().orEmpty()
            return try {
                val names = mutableListOf<String>()
                // 从 BaseDexClassLoader 的 pathList 里拿 dex 文件名（类枚举在 Android 上不可靠，
                // 退一步：用 ClassLoader 的已加载包 + 常见反射手段）。
                val cl = classLoader
                val pathListField = runCatching { cl.javaClass.getDeclaredField("pathList") }.getOrNull()
                if (pathListField != null) {
                    pathListField.isAccessible = true
                    val pathList = runCatching { pathListField.get(cl) }.getOrNull()
                    val dexElementsField = pathList?.javaClass?.getDeclaredField("dexElements")
                    dexElementsField?.isAccessible = true
                    val elements = runCatching { dexElementsField?.get(pathList) as? Array<*> }.getOrNull()
                    elements?.forEach { element ->
                        val dexFile = runCatching {
                            element?.javaClass?.getDeclaredField("dexFile")?.apply { isAccessible = true }?.get(element)
                        }.getOrNull()
                        runCatching {
                            dexFile?.javaClass?.getMethod("entries")?.invoke(dexFile)?.let { it as? java.util.Enumeration<*> }
                        }.getOrNull()?.let { e ->
                            while (e.hasMoreElements()) {
                                val name = e.nextElement()?.toString() ?: continue
                                if (prefix.isBlank() || name.startsWith(prefix)) names += name
                                if (names.size >= 500) break
                            }
                        }
                    }
                }
                cx.newArray(scope, names.distinct().toTypedArray())
            } catch (throwable: Throwable) {
                JavaBridge.throwOrReturn(cx, JavaBridge.describe(throwable))
            }
        }
    }

    /** 读静态字段：javaField("android.os.Build", "DEVICE")。很多常量是字段不是方法。 */
    private class FieldRead(
        private val classLoader: ClassLoader,
        private val scope: Scriptable,
    ) : BaseFunction() {
        override fun getFunctionName(): String = "javaField"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            val className = args.getOrNull(0)?.toString() ?: return JavaBridge.fail(cx, "javaField 需要类名")
            val fieldName = args.getOrNull(1)?.toString() ?: return JavaBridge.fail(cx, "javaField 需要字段名")
            return try {
                val target = resolveClass(className) ?: Class.forName(className, true, classLoader)
                val field = target.getField(fieldName)
                field.isAccessible = true
                // P4：field.get(null) 会触发类的静态初始化块。某些类的静态初始化依赖
                // 主线程 Handler，在工作线程里跑会直接阻塞（实测米家进程被杀）。
                // 这里给独立线程 + 3 秒硬超时：拿不到就返回超时错误，不拖垮进程。
                val holder = arrayOfNulls<Any>(1)
                val err = arrayOfNulls<Throwable>(1)
                val worker = Thread {
                    try {
                        holder[0] = field.get(null)
                    } catch (t: Throwable) {
                        err[0] = t
                    }
                }
                worker.isDaemon = true
                worker.start()
                worker.join(3000)
                if (worker.isAlive) {
                    return JSONObject()
                        .put("ok", false)
                        .put("code", "STATIC_INIT_TIMEOUT")
                        .put("message", "读取 $className.$fieldName 触发静态初始化超过 3 秒，已放弃（不阻塞进程）")
                        .toString()
                }
                err[0]?.let { throw it }
                toJs(cx, scope, holder[0])
            } catch (throwable: Throwable) {
                JavaBridge.throwOrReturn(cx, JavaBridge.describe(throwable))
            }
        }
    }

    private class InstanceCall(private val scope: Scriptable) : BaseFunction() {
        override fun getFunctionName(): String = "javaCallOn"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            val receiver = args.getOrNull(0) ?: return JavaBridge.fail(cx, "javaCallOn 需要实例")
            // 也接受 handle 字符串（如 "h3"）：先查 handle 表，命中就直接用原始对象。
            // 否则 javaCallOn("h3","isOpen") 会把字符串当 receiver，反射必然失败。
            val receiverResolved = when (receiver) {
                is CharSequence -> {
                    val key = receiver.toString()
                    if (key.matches(Regex("h\\d+"))) handleRegistry[key] ?: receiver else receiver
                }
                else -> receiver
            }
            val methodName = args.getOrNull(1)?.toString() ?: return JavaBridge.fail(cx, "javaCallOn 需要方法名")
            val methodArgs = when (val raw = args.getOrNull(2)) {
                null, org.mozilla.javascript.Undefined.instance -> emptyList()
                is org.mozilla.javascript.NativeArray -> {
                    val arr: Scriptable = raw
                    val len = (ScriptableObject.getProperty(arr, "length") as? Number)?.toInt() ?: 0
                    (0 until len).map { idx -> ScriptableObject.getProperty(arr, idx) }
                }
                else -> listOf(raw)
            }
            return try {
                // 必须走 toJava 还原原始 Java 引用：jsToJava 拿到的是包装层，
                // 反射调实例方法时 receiver 类型不对，会全部失败。
                val target = toJava(receiverResolved) ?: return JavaBridge.fail(cx, "实例为空")
                // P3：receiver 是 Class 时调静态方法（javaCallOn(cls, "parse", [...])）。
                // 早先一律当实例调，报「找不到方法 org.json.JSONObject.parse」。
                if (target is Class<*>) {
                    val staticArgs = methodArgs.map { toJava(it) }.toTypedArray()
                    val staticMethod = findMethodForArgs(target, methodName, staticArgs)
                        ?: findMethod(target, methodName, staticArgs.size)
                        ?: return JavaBridge.throwOrReturn(cx, "找不到静态方法 ${target.name}.$methodName/${methodArgs.size}")
                    staticMethod.isAccessible = true
                    return toJs(cx, scope, staticMethod.invoke(null, *staticArgs))
                }
                val javaArgs = methodArgs.map { toJava(it) }.toTypedArray()
                val method = findMethodForArgs(target.javaClass, methodName, javaArgs)
                    ?: return JavaBridge.throwOrReturn(cx, "找不到方法 ${target.javaClass.name}.$methodName/${methodArgs.size}")
                method.isAccessible = true
                toJs(cx, scope, method.invoke(target, *javaArgs))
            } catch (throwable: Throwable) {
                JavaBridge.throwOrReturn(cx, JavaBridge.describe(throwable))
            }
        }
    }

    /**
     * 动态创建接口实例：javaProxy("com.x.Listener", {onSuccess: fn, onFailure: fn})。
     *
     * 为什么必须有：安卓里大量回调都是接口（Callback、OnClickListener、ResultListener…），
     * Rhino 无法直接 new 出 Java 接口，导致「能读不能写」——反射调用带回调的方法时
     * 传 null 会被吞掉、什么也不发生（实测 setDeviceProps 传 null 只是本地空转）。
     * 这里用 java.lang.reflect.Proxy 造真实实现：接口方法名 → JS 函数，逐个分发。
     */
    /**
     * javaSubclass(抽象类名[, handlers]) —— 运行时生成抽象类的子类实例。
     *
     * 为什么需要它：`javaProxy` 只能代理**接口**。但很多库的回调是
     * **Parcelable 抽象类**（如 IResultListener，只有一个抽象方法 onResult(Bundle)），
     * Java 的动态代理对抽象类无能为力，必须真的生成一个子类。
     *
     * 用 DexMaker 生成一个只实现抽象方法的子类；未提供 handler 的抽象方法
     * 返回类型默认值（void→null，基本类型→0/false）。
     */
    private class SubclassOf(
        private val classLoader: ClassLoader,
        private val scope: Scriptable,
    ) : BaseFunction() {
        override fun getFunctionName(): String = "javaSubclass"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            val abstractName = args.getOrNull(0)?.toString()
                ?: return JavaBridge.fail(cx, "javaSubclass 需要抽象类名")
            val handlers = args.getOrNull(1)
            return try {
                val superClass = resolveClass(abstractName)
                    ?: Class.forName(abstractName, true, classLoader)
                if (!java.lang.reflect.Modifier.isAbstract(superClass.modifiers)) {
                    // 非抽象类直接 new
                    val ctor = superClass.getDeclaredConstructor().apply { isAccessible = true }
                    return toJs(cx, scope, ctor.newInstance())
                }
                // 收集所有抽象方法（含继承来的）
                val abstractMethods = mutableListOf<java.lang.reflect.Method>()
                var cur: Class<*>? = superClass
                while (cur != null && cur != Any::class.java) {
                    for (m in cur.declaredMethods) {
                        if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) {
                            if (abstractMethods.none { it.name == m.name &&
                                    it.parameterTypes.contentEquals(m.parameterTypes) }) {
                                abstractMethods.add(m)
                            }
                        }
                    }
                    cur = cur.superclass
                }
                if (abstractMethods.isEmpty()) {
                    return JavaBridge.fail(cx, "$abstractName 没有抽象方法，直接 javaNew")
                }

                val generatedName = "eta.gen.Sub_${superClass.simpleName}_${System.nanoTime()}"
                // 父类多半来自目标应用的动态 dex：生成的子类必须用同一个 ClassLoader，
                // 否则会 ClassNotFoundException: xxx_1_Proxy（dex 写出来了但加载不到父类）。
                val parentLoader = superClass.classLoader ?: classLoader
                val proxy = com.android.dx.stock.ProxyBuilder.forClass(superClass)
                    .parentClassLoader(parentLoader)
                    .dexCache(File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "rhinobridge-dex").apply { mkdirs() })
                    .constructorArgTypes()
                    .constructorArgValues()
                    .handler(object : java.lang.reflect.InvocationHandler {
                        override fun invoke(proxyObj: Any?, method: java.lang.reflect.Method, methodArgs: Array<out Any?>?): Any? {
                            return when (method.name) {
                                "toString" -> "eta-subclass(${superClass.name})"
                                "hashCode" -> System.identityHashCode(proxyObj)
                                "equals" -> proxyObj === methodArgs?.firstOrNull()
                                else -> {
                                    val fn = (handlers as? Scriptable)?.let {
                                        ScriptableObject.getProperty(it, method.name)
                                    }
                                    if (fn != null && fn !== Scriptable.NOT_FOUND && fn is org.mozilla.javascript.Function) {
                                        val nested = RhinoContext.enter()
                                        try {
                                            nested.optimizationLevel = -1
                                            val jsArgs: Array<Any?> =
                                                (methodArgs ?: emptyArray()).map { toJs(cx, scope, it) }.toTypedArray()
                                            fn.call(cx, scope, scope, jsArgs)
                                        } finally {
                                            RhinoContext.exit()
                                        }
                                    }
                                    when (method.returnType) {
                                        java.lang.Void.TYPE -> null
                                        java.lang.Boolean.TYPE -> false
                                        java.lang.Byte.TYPE -> 0.toByte()
                                        java.lang.Short.TYPE -> 0.toShort()
                                        java.lang.Integer.TYPE -> 0
                                        java.lang.Long.TYPE -> 0L
                                        java.lang.Float.TYPE -> 0f
                                        java.lang.Double.TYPE -> 0.0
                                        java.lang.Character.TYPE -> '\u0000'
                                        else -> null
                                    }
                                }
                            }
                        }
                    })
                    .build()
                // 直接返回实例本体：调用方要能立刻 javaCallOn(它, ...)。
                // 元信息放在实例的 toString 上（DexMaker 的 handler 已处理 toString）。
                toJs(cx, scope, proxy)
            } catch (throwable: Throwable) {
                JavaBridge.throwOrReturn(cx, JavaBridge.describe(throwable))
            }
        }
    }

    /**
     * javaMethodSignature(类名, 方法名[, 参数个数])
     *
     * 返回该方法的完整签名，**参数类型带全限定名**。
     *
     * 解决的核心痛点：库里的回调接口常常只在方法签名里以简名出现
     * （如 `b:IResultListener`），而 `javaProxy` 必须传全限定名；
     * 这些接口又往往在动态加载的 dex 里，`javaListClasses` 枚举不到。
     * 只有反射能拿到真名。
     */
    private class MethodSignature(
        private val classLoader: ClassLoader,
        private val scope: Scriptable,
    ) : BaseFunction() {
        override fun getFunctionName(): String = "javaMethodSignature"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            val className = args.getOrNull(0)?.toString()
                ?: return JavaBridge.fail(cx, "javaMethodSignature 需要类名")
            val methodName = args.getOrNull(1)?.toString()
                ?: return JavaBridge.fail(cx, "javaMethodSignature 需要方法名")
            val wantCount = (args.getOrNull(2) as? Number)?.toInt()
            return try {
                val cls = resolveClass(className)
                    ?: Class.forName(className, true, classLoader)
                val found = (cls.declaredMethods.toList() + cls.methods.toList())
                    .filter { it.name == methodName }
                    .filter { wantCount == null || it.parameterCount == wantCount }
                    .distinctBy { it.parameterTypes.joinToString(",") { t -> t.name } }
                val arr = JSONArray()
                for (m in found) {
                    arr.put(
                        JSONObject()
                            .put("method", m.name)
                            .put("returns", m.returnType.name)
                            .put(
                                "params",
                                JSONArray(m.parameterTypes.map { it.name }),
                            )
                            .put("descriptor", m.toGenericString()),
                    )
                }
                JSONObject()
                    .put("ok", true)
                    .put("class", cls.name)
                    .put("count", found.size)
                    .put("signatures", arr)
                    .toString()
            } catch (throwable: Throwable) {
                JavaBridge.throwOrReturn(cx, JavaBridge.describe(throwable))
            }
        }
    }

    /**
     * javaProxyFor(类名, 方法名[, 参数个数][, handlers])
     *
     * 按「方法名」反查该方法的**第 N 个参数如果是接口**，就给它造一个代理。
     * 省掉手动找全限定名的步骤，专门对付 `startXXX(int, int, IResultListener)` 这类。
     *
     * 返回 {ok, iface, proxy, signature}
     */
    private class ProxyForMethod(
        private val classLoader: ClassLoader,
        private val scope: Scriptable,
    ) : BaseFunction() {
        override fun getFunctionName(): String = "javaProxyFor"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            val className = args.getOrNull(0)?.toString()
                ?: return JavaBridge.fail(cx, "javaProxyFor 需要类名")
            val methodName = args.getOrNull(1)?.toString()
                ?: return JavaBridge.fail(cx, "javaProxyFor 需要方法名")
            val wantCount = (args.getOrNull(2) as? Number)?.toInt()
            val handlers = args.getOrNull(3)
            return try {
                val cls = resolveClass(className)
                    ?: Class.forName(className, true, classLoader)
                val candidates = (cls.declaredMethods.toList() + cls.methods.toList())
                    .filter { it.name == methodName }
                    .filter { wantCount == null || it.parameterCount == wantCount }
                if (candidates.isEmpty()) {
                    return JavaBridge.fail(cx, "找不到方法 $className.$methodName")
                }
                // 优先挑带接口参数的那个重载
                val withIface = candidates.firstOrNull { m ->
                    m.parameterTypes.any { it.isInterface }
                } ?: candidates.first()
                val ifaceType = withIface.parameterTypes.firstOrNull { it.isInterface }
                    ?: return JavaBridge.fail(
                        cx,
                        "$className.$methodName 的参数里没有接口类型",
                    )
                val handler = java.lang.reflect.InvocationHandler { proxy, method, methodArgs ->
                    when (method.name) {
                        "toString" -> "eta-proxy(${ifaceType.simpleName})"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === methodArgs?.firstOrNull()
                        else -> {
                            val fn = (handlers as? Scriptable)?.let {
                                ScriptableObject.getProperty(it, method.name)
                            }
                            if (fn != null && fn !== Scriptable.NOT_FOUND && fn is org.mozilla.javascript.Function) {
                                val nested = RhinoContext.enter()
                                try {
                                    nested.optimizationLevel = -1
                                    val jsArgs: Array<Any?> =
                                        (methodArgs ?: emptyArray()).map { toJs(cx, scope, it) }.toTypedArray()
                                    fn.call(cx, scope, scope, jsArgs)
                                } finally {
                                    RhinoContext.exit()
                                }
                            }
                            when (method.returnType) {
                                java.lang.Void.TYPE -> null
                                java.lang.Boolean.TYPE -> false
                                java.lang.Byte.TYPE -> 0.toByte()
                                java.lang.Short.TYPE -> 0.toShort()
                                java.lang.Integer.TYPE -> 0
                                java.lang.Long.TYPE -> 0L
                                java.lang.Float.TYPE -> 0f
                                java.lang.Double.TYPE -> 0.0
                                java.lang.Character.TYPE -> '\u0000'
                                else -> null
                            }
                        }
                    }
                }
                val instance = java.lang.reflect.Proxy.newProxyInstance(
                    ifaceType.classLoader ?: classLoader,
                    arrayOf(ifaceType),
                    handler,
                )
                JSONObject()
                    .put("ok", true)
                    .put("iface", ifaceType.name)
                    .put("method", withIface.name)
                    .put(
                        "params",
                        JSONArray(withIface.parameterTypes.map { it.name }),
                    )
                    .put("proxy", toJs(cx, scope, instance))
                    .toString()
            } catch (throwable: Throwable) {
                JavaBridge.throwOrReturn(cx, JavaBridge.describe(throwable))
            }
        }
    }

    private class ProxyOf(
        private val classLoader: ClassLoader,
        private val scope: Scriptable,
    ) : BaseFunction() {
        override fun getFunctionName(): String = "javaProxy"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            val ifaceName = args.getOrNull(0)?.toString()
                ?: return JavaBridge.fail(cx, "javaProxy 需要接口名")
            // 第二参数：{方法名: 函数}，也可以只给返回值常量
            val handlers = args.getOrNull(1)
            return try {
                val iface = Class.forName(ifaceName, true, classLoader)
                if (!iface.isInterface) {
                    return JavaBridge.fail(cx, "$ifaceName 不是接口")
                }
                val handler = java.lang.reflect.InvocationHandler { proxy, method, methodArgs ->
                    when (method.name) {
                        "toString" -> "eta-proxy(${iface.simpleName})"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === methodArgs?.firstOrNull()
                        else -> {
                            val fn = (handlers as? Scriptable)?.let {
                                ScriptableObject.getProperty(it, method.name)
                            }
                            if (fn != null && fn !== Scriptable.NOT_FOUND && fn is org.mozilla.javascript.Function) {
                                // 回调可能在任意线程回来：Rhino 上下文必须在本线程进入。
                                val nested = RhinoContext.enter()
                                try {
                                    nested.optimizationLevel = -1
                                    val jsArgs: Array<Any?> = (methodArgs ?: emptyArray()).map { toJs(cx, scope, it) }.toTypedArray()
                                    fn.call(cx, scope, scope, jsArgs)
                                } finally {
                                    RhinoContext.exit()
                                }
                            }
                            defaultValue(method.returnType)
                        }
                    }
                }
                val instance = java.lang.reflect.Proxy.newProxyInstance(
                    iface.classLoader ?: classLoader,
                    arrayOf(iface),
                    handler,
                )
                toJs(cx, scope, instance)
            } catch (throwable: Throwable) {
                JavaBridge.throwOrReturn(cx, JavaBridge.describe(throwable))
            }
        }

        /** 回调方法的返回值默认值：void→null，基本类型→0/false。 */
        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Void.TYPE -> null
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }

    /**
     * 枚举 dex 里的真实类名：javaListClasses("com.xiaomi", "rpc")。
     *
     * 为什么要有：靠猜类名不可行（ClassNotFoundException 一片），
     * 而「扫堆」只能看到已实例化的类型。这里直接遍历应用 ClassLoader 的
     * dexElements → DexFile.entries()，拿到全量类名后按前缀/关键词过滤。
     * 全程运行时反射，不碰 APK 文件、不需要反编译。
     */
    private class ListClasses(
        private val classLoader: ClassLoader,
        private val scope: Scriptable,
    ) : BaseFunction() {
        override fun getFunctionName(): String = "javaListClasses"

        override fun call(
            cx: RhinoContext,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>,
        ): Any? {
            val prefix = args.getOrNull(0)?.toString().orEmpty()
            val keyword = args.getOrNull(1)?.toString()?.lowercase().orEmpty()
            // P6：默认上限从 2000 收到 5000 仍不够，真正的问题是**一次性收集**。
            // 这里保留 limit 语义（最多收多少个类名），但收集过程不再无限增长：
            // LinkedHashSet 装 5 万个短字符串约几 MB，可接受；20 万会 OOM。
            val limit = (args.getOrNull(2) as? Number)?.toInt() ?: 5000
            return try {
                val diag = JSONObject()
                val names = LinkedHashSet<String>()
                val loader = classLoader
                diag.put("loaderClass", loader.javaClass.name)

                // 逐个候选 Loader 试：目标应用可能是 BaseDexClassLoader 或其子类，
                // 也可能被 ROM 换成别的实现。找到能吐出类名的那个为止。
                val loaders = LinkedHashSet<ClassLoader>()
                loaders += loader
                runCatching { loader.parent }.getOrNull()?.let { loaders += it }
                // 关键：宿主拿到的 classLoader 未必是目标应用自己的 loader（实测 javaCall
                // 用宿主 loader 找不到目标应用的类，抛 ClassNotFoundException）。
                // 这里把「上下文 ClassLoader」也加进来，并去重。
                runCatching { Thread.currentThread().contextClassLoader }.getOrNull()?.let { loaders += it }
                // 再从若干目标类反向取它们的 loader（最可靠）。
                for (probe in PROBE_CLASSES) {
                    runCatching { Class.forName(probe, false, loader).classLoader }.getOrNull()?.let { loaders += it }
                    runCatching { Class.forName(probe, false, Thread.currentThread().contextClassLoader).classLoader }
                        .getOrNull()?.let { loaders += it }
                }

                val tried = JSONArray()
                for (candidate in loaders) {
                    if (names.size >= limit) break
                    val files = dexFilesOf(candidate, diag) ?: continue
                    tried.put(candidate.javaClass.name + ":" + files.size)
                    diag.put("elementsCount", files.size)
                    for (dexFile in files) {
                        val classNames = classNamesOf(dexFile, diag)
                        for (name in classNames) {
                            if (prefix.isNotEmpty() && !name.startsWith(prefix)) continue
                            if (keyword.isNotEmpty() && !name.lowercase().contains(keyword)) continue
                            names += name
                            if (names.size >= limit) break
                        }
                        if (names.size >= limit) break
                    }
                }
                diag.put("tried", tried)
                if (names.isEmpty()) {
                    return diag.put("ok", false).put("code", "NO_CLASSES")
                }
                // 回 JSON 字符串而不是 JS 数组：JS 数组跨壳传递会被再包一层，
                // 调用方 String(r) 直接抛 msg.invalid.type（实测）。字符串最稳。
                //
                // P6：一次性拼 20 万个类名的 JSON 会直接 OOM（实测网易云被打挂）。
                // 这里按总字符数封顶，超出就截断并标记 truncated，配套 offset 分页。
                val offset = (args.getOrNull(3) as? Number)?.toInt() ?: 0
                val all = names.toList()
                val page = if (offset in all.indices) all.drop(offset) else emptyList()
                val maxChars = 2_000_000
                val out = mutableListOf<String>()
                var used = 0
                for (name in page) {
                    used += name.length + 3
                    if (used > maxChars) break
                    out += name
                }
                org.mozilla.javascript.Context.javaToJS(
                    JSONObject()
                        .put("ok", true)
                        .put("total", all.size)
                        .put("offset", offset)
                        .put("count", out.size)
                        .put("truncated", offset + out.size < all.size)
                        .put("nextOffset", offset + out.size)
                        .put("classes", JSONArray(out))
                        .toString(),
                    scope,
                )
            } catch (throwable: Throwable) {
                JSONObject()
                    .put("ok", false)
                    .put("code", "LIST_FAILED")
                    .put("message", JavaBridge.describe(throwable))
            }
        }

        /**
         * 从一个 ClassLoader 里挖出 DexFile 列表。
         *
         * 各代 Android / 各 ROM 的字段名不一致（pathList→dexElements→dexFile 是 AOSP，
         * 但元素类型可能是 Element / DexPathList$Element / List），所以这里不硬编码，
         * 沿对象图找「名字里带 dex 的字段」，命中 DexFile 就收。
         */
        private fun dexFilesOf(loader: ClassLoader, diag: JSONObject): List<Any>? {
            val pathList = fieldAny(loader, "pathList") ?: run {
                diag.put("noPathList", loader.javaClass.name)
                return null
            }
            val elementsRaw = fieldAny(pathList, "dexElements") ?: run {
                diag.put("noDexElements", pathList.javaClass.name)
                return null
            }
            val elements: List<Any> = when (elementsRaw) {
                is Array<*> -> elementsRaw.filterNotNull()
                is Collection<*> -> elementsRaw.filterNotNull()
                else -> {
                    diag.put("badElements", elementsRaw.javaClass.name)
                    return null
                }
            }
            val out = mutableListOf<Any>()
            for (element in elements) {
                // 直接在元素上找 dexFile
                fieldAny(element, "dexFile")?.let { out += it }
                // 有的实现把 DexFile 挂在更深的字段里：浅扫一层
                if (fieldAny(element, "dexFile") == null) {
                    runCatching {
                        element.javaClass.declaredFields.forEach { f ->
                            if (f.name.lowercase().contains("dex")) {
                                f.isAccessible = true
                                f.get(element)?.let { v ->
                                    if (v.javaClass.name.contains("DexFile")) out += v
                                }
                            }
                        }
                    }
                }
            }
            // P5：诊断信息细化——记录每个 Element 的 dexFile 取到什么，
            // 早先只记 files.size，多 dex 应用（米家 14 个）看到 1 会误判成"只有一个 dex"。
            if (out.size != elements.size) {
                diag.put("elementsTotal", elements.size)
                diag.put("dexFilesGot", out.size)
            }
            if (out.isEmpty()) diag.put("emptyElements", elements.size)
            return out.takeIf { it.isNotEmpty() }
        }

        /**
         * 从 DexFile 里取类名列表。
         *
         * Android 8+ 的 DexFile.entries() 对应用私有 dex 会抛 IllegalArgumentException(null)，
         * 必须改用 getClassNameList()（返回 String[] 的私有方法，走 Class 对象反射调用）。
         * 老实现只试 entries()，所以永远拿不到类名（实测 LIST_FAILED: IllegalArgumentException: null）。
         */
        private fun classNamesOf(dexFile: Any, diag: JSONObject): List<String> {
            // 1) Android 8+：DexFile.getClassNameList()（私有，返回 String[]）
            runCatching {
                val method = dexFile.javaClass.getDeclaredMethod("getClassNameList")
                method.isAccessible = true
                val result = method.invoke(dexFile)
                if (result is Array<*>) {
                    return result.mapNotNull { it?.toString() }
                }
            }.onFailure { diag.put("getClassNameListErr", it.javaClass.simpleName + ": " + (it.message ?: "")) }

            // 2) 老路：entries()（Android 7 及更早）
            runCatching {
                val entries = dexFile.javaClass.getMethod("entries").invoke(dexFile) as? java.util.Enumeration<*>
                    ?: return@runCatching
                val out = mutableListOf<String>()
                while (entries.hasMoreElements()) {
                    entries.nextElement()?.toString()?.let { out += it }
                }
                if (out.isNotEmpty()) return out
            }.onFailure { diag.put("entriesErr", it.javaClass.simpleName + ": " + (it.message ?: "")) }

            diag.put("classNamesEmpty", dexFile.javaClass.name)
            return emptyList()
        }

        private companion object {
            /** 用来反查「目标应用真实 ClassLoader」的探针类。 */
            val PROBE_CLASSES = listOf(
                "android.app.Application",
                "android.app.ActivityThread",
                "com.xiaomi.smarthome.device.MiioDeviceV2",
            )
        }

        /** 读字段：先本类 declared，再沿父类。找不到返回 null。 */
        private fun fieldAny(target: Any, name: String): Any? {
            var cls: Class<*>? = target.javaClass
            while (cls != null) {
                val field = runCatching { cls.getDeclaredField(name) }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    return runCatching { field.get(target) }.getOrNull()
                }
                cls = cls.superclass
            }
            return null
        }
    }
}
