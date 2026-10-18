package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The reflective face of the ECARX steering-wheel key interception, recovered from a third-party
 * app that runs on this head unit (see 适配文件/方控与语音助手按键-反编译结论.md).
 *
 * On 领克 / 亿咖通 (820A) head units the steering wheel does NOT deliver standard Android key codes
 * through a normal `Activity.dispatchKeyEvent`: the wheel reports keys in the ECARX 200000-offset
 * space and only surfaces them through the ROM's key-interception API. A third-party app that only
 * listens to standard key codes therefore never sees a wheel press (the exact "方控学习后按键无反应"
 * symptom). The reference app fixes this by registering the nine wheel keys with
 * `com.ecarx.xui.adaptapi.input.Input.requestKeysInterception(int[], IKeyCallback)`.
 *
 * Everything is reflection-only because `Input`/`IKeyCallback` live in the head-unit system image and
 * are never packaged in this APK (a compile-time reference would crash class-init elsewhere). Every
 * call tolerates absence; a head unit without the API simply yields no interception and the normal
 * key path is left untouched.
 */
object EcarxSwKeyIntercept {
    private const val TAG = "DiPlay-Ecarx-SwKey"

    /** The nine wheel keys the reference app intercepts (media.oneOsKey, media.smali:22626). */
    private val oneOsKey: IntArray = intArrayOf(
        0x30D98, // 200088 prev
        0x30D97, // 200087 next
        0x30D95, // 200085 play/pause
        0x30E27, // 200231 voice
        0x30DE4, // 200164 ?
        0x30ED0, // 200400 ?
        0x30D58, // 200024 volume+
        0x30D59, // 200025 volume-
        0x77,    // 119 ?
    )

    /** A single wheel key press (or release) surfaced by the platform callback. */
    interface Listener {
        /** A wheel key went down; [keyCode] is in the ECARX 200000-offset space. Return true to consume. */
        fun onKeyPressed(keyCode: Int): Boolean
        fun onKeyReleased(keyCode: Int): Boolean
    }

    data class Result(val ok: Boolean, val reason: String)

    /** Reflectively registers the nine wheel keys; the returned handle must be released on teardown. */
    fun register(context: Context, listener: Listener): Handle? {
        val input = createInput(context) ?: return null
        val callbackClass = callbackClass() ?: return null
        val proxy = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
            CallbackHandler(listener),
        )
        runCatching {
            lookup(input, "requestKeysInterception", arrayOf(IntArray::class.java, callbackClass))
                ?.invoke(input, oneOsKey, proxy)
        }.getOrElse {
            Log.i(TAG, "requestKeysInterception failed: ${describe(it)}")
            return null
        }
        // The reference app treats a non-throwing call as accepted; the returned int[] (null on success) holds
        // the keys the ROM refused, which is not acted on here.
        return Handle(input, proxy, callbackClass) { inputObj, callbackObj ->
            runCatching {
                lookup(inputObj, "releaseKeysInterception", arrayOf(IntArray::class.java, callbackClass))
                    ?.invoke(inputObj, oneOsKey, callbackObj)
            }.isSuccess
        }
    }

    /** A held interception registration; [release] hands the keys back to the platform. */
    class Handle internal constructor(
        private val input: Any,
        private val callback: Any,
        private val callbackClass: Class<*>,
        private val release: (Any, Any) -> Boolean,
    ) {
        fun release() {
            runCatching { release(input, callback) }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun createInput(context: Context): Any? {
        val inputClass = runCatching { Class.forName("com.ecarx.xui.adaptapi.input.Input") }.getOrNull()
            ?: return null
        return runCatching {
            inputClass.getMethod("create", Context::class.java).invoke(null, context)
        }.getOrElse {
            Log.i(TAG, "Input.create failed: ${describe(it)}")
            null
        }
    }

    private fun callbackClass(): Class<*>? =
        runCatching { Class.forName("com.ecarx.xui.adaptapi.input.IKeyCallback") }.getOrNull()

    private fun lookup(target: Any, name: String, params: Array<Class<*>>): Method? =
        runCatching { target.javaClass.getMethod(name, *params) }
            .recoverCatching { target.javaClass.getDeclaredMethod(name, *params).also { it.isAccessible = true } }
            .getOrNull()

    private fun describe(error: Throwable): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    private class CallbackHandler(private val listener: Listener) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "onKeyPressed" -> listener.onKeyPressed((args?.firstOrNull() as? Int) ?: -1)
            "onKeyReleased" -> listener.onKeyReleased((args?.firstOrNull() as? Int) ?: -1)
            "toString" -> "EcarxSwKeyCallback"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                else -> null
            }
        }
    }
}
