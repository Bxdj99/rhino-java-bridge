package io.github.rhinobridge.demo

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.rhinobridge.RhinoBridge

/**
 * 最小可跑示例：点按钮依次跑几个脚本，看结果。
 *
 * 重点是每个示例都在演示 **为什么这样做比直接反射方便**：
 * 不用改宿主代码重编，脚本写完立刻能试。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private val bridge by lazy { RhinoBridge(classLoader = javaClass.classLoader!!) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply {
            textSize = 12f
            setPadding(32, 32, 32, 32)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        samples.forEach { (title, runnable) ->
            container.addView(Button(this).apply {
                text = title
                setOnClickListener {
                    val result = runCatching { runnable() }
                        .getOrElse { "异常：${it.message}" }
                    append("【$title】\n$result\n\n")
                }
            })
        }

        container.addView(ScrollView(this).apply { addView(output) })
        setContentView(container)
    }

    private fun append(text: String) {
        output.append(text)
        Log.i("RhinoBridgeDemo", text)
    }

    /** 每个示例：标题 → 执行。 */
    private val samples: List<Pair<String, () -> String>> = listOf(

        "1. 读静态字段" to {
            bridge.eval(
                """
                ({run: function() {
                    return {
                        brand: javaField("android.os.Build", "BRAND"),
                        model: javaField("android.os.Build", "MODEL"),
                        sdk:   javaField("android.os.Build", "SDK_INT")
                    };
                }})
                """.trimIndent(),
                "run",
            )
        },

        "2. 调静态方法 + 返回值可读" to {
            bridge.eval(
                """
                ({run: function() {
                    return {
                        now:  String(javaCall("java.lang.System", "currentTimeMillis")),
                        pid:  String(javaCall("android.os.Process", "myPid")),
                        max:  String(javaCall("java.lang.Runtime", "getRuntime").toString().length)
                    };
                }})
                """.trimIndent(),
                "run",
            )
        },

        "3. 构造对象 + 实例方法（坑：集合引用）" to {
            // 这个示例演示最容易踩的坑：
            // 早期实现把 ArrayList 转成 JS 数组，原始对象丢失，
            // 于是 add() 之后 size() 永远返回 0。
            bridge.eval(
                """
                ({run: function() {
                    var list = javaNew("java.util.ArrayList", []);
                    javaCallOn(list, "add", ["a"]);
                    javaCallOn(list, "add", ["b"]);
                    return {
                        size:  javaCallOn(list, "size", []),
                        first: String(javaCallOn(list, "get", [0]))
                    };
                }})
                """.trimIndent(),
                "run",
            )
        },

        "4. 列类的成员（不反编译）" to {
            bridge.eval(
                """
                ({run: function() {
                    return { members: javaFind("android.os.Build", "MANUFACT") };
                }})
                """.trimIndent(),
                "run",
            )
        },

        "5. 查 ClassLoader（动态模块必备）" to {
            // 有些应用的业务类在独立 ClassLoader 上，
            // 用默认 loader 会 ClassNotFoundException，但类其实在内存里。
            bridge.eval(
                """
                ({run: function() {
                    return javaLoaders();
                }})
                """.trimIndent(),
                "run",
            )
        },

        "6. 按类型扫堆找实例" to {
            // 「对象在内存里，但不知道藏在哪个字段」时的终极手段。
            // 注意：扫描有上限，别在大进程上直接开太大。
            bridge.eval(
                """
                ({run: function() {
                    return javaFindInstances({ cls: "java.lang.StringBuilder", limit: 5, maxScan: 5000 });
                }})
                """.trimIndent(),
                "run",
            )
        },

        "7. 深路径读字段" to {
            bridge.eval(
                """
                ({run: function() {
                    // 用 -> 分段，避开包名/类名里的点号被误拆
                    var r = javaNew("java.lang.StringBuilder", ["hi"]);
                    return { value: String(javaCallOn(r, "toString", [])) };
                }})
                """.trimIndent(),
                "run",
            )
        },
    )
}
