package com.shilapi.xcertplay.hud

import android.util.Log
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Diagnostic probe for the opaque `ILaneInfo` contract.
 *
 * ECARX ships no SDK docs, so the only definition of `ILaneInfo` is the system image itself. The
 * Settings button first dumps the interface as the runtime sees it, then pushes one `ILaneInfo[]`
 * of spies through `updateLaneInfo`. Every call the platform makes on a spy is recorded with the
 * value the spy returned, which names the fields the car actually reads; the values can then be
 * compared with Apple's 0x5204 lane data.
 *
 * Nothing here is used by the live guidance path. A failure only affects the diagnostic report.
 */
internal object EcarxLaneProbe {
    private const val TAG = "DiPlay-ECARX-Lane"
    private const val SPY_LANES = 3

    fun probe(navi: Any): String {
        val lane = runCatching { Class.forName(EcarxCapabilities.LANE_INFO) }.getOrNull()
            ?: return "ILaneInfo class not found on this head unit: ${EcarxCapabilities.LANE_INFO}"
        val report = buildString {
            appendLine("navi: " + navi.javaClass.name)
            appendLine("class: " + lane.name)
            appendLine("interface: " + lane.isInterface)
            appendLine("modifiers: " + Modifier.toString(lane.modifiers))
            appendLine("super: " + (lane.superclass?.name ?: "none"))
            appendLine("interfaces: " + lane.interfaces.joinToString { it.name }.ifEmpty { "none" })
            appendLine()
            appendLine("declared methods (" + lane.declaredMethods.size + "):")
            lane.declaredMethods.sortedBy { it.name }.forEach { appendLine("  " + signature(it)) }
            val inherited = lane.methods
                .filter { it.declaringClass != lane && it.declaringClass != Any::class.java }
                .sortedBy { it.name }
            if (inherited.isNotEmpty()) {
                appendLine()
                appendLine("inherited methods (" + inherited.size + "):")
                inherited.forEach {
                    appendLine("  " + signature(it) + "  [" + it.declaringClass.simpleName + "]")
                }
            }
            if (lane.declaredFields.isNotEmpty()) {
                appendLine()
                appendLine("fields:")
                lane.declaredFields.forEach {
                    appendLine("  " + Modifier.toString(it.modifiers) + " " + it.type.simpleName + " " + it.name)
                }
            }
            if (!lane.isInterface) {
                appendLine()
                appendLine("constructors:")
                lane.declaredConstructors.forEach { constructor ->
                    appendLine("  (" + constructor.parameterTypes.joinToString(",") { it.simpleName } + ")")
                }
            }
            appendLine()
            appendLine("spy:")
            appendLine(spy(navi, lane))
        }.trimEnd()
        Log.i(TAG, "lane probe:\n$report")
        return report
    }

    private fun spy(navi: Any, lane: Class<*>): String {
        if (!lane.isInterface) {
            return "skipped: ILaneInfo is a class, not an interface; inspect the constructors above"
        }
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val array = ReflectArray.newInstance(lane, SPY_LANES)
        for (index in 0 until SPY_LANES) {
            val handler = InvocationHandler { proxy, method, args ->
                val value = if (method.name == "equals") {
                    proxy === args?.firstOrNull()
                } else {
                    invocationValue(proxy, method, index)
                }
                calls += "lane$index." + method.name + "(" + (args?.joinToString(",") ?: "") +
                    ") -> " + render(value)
                value
            }
            val spy = runCatching {
                Proxy.newProxyInstance(lane.classLoader, arrayOf(lane), handler)
            }.getOrElse {
                return "proxy failed: " + describe(it)
            }
            ReflectArray.set(array, index, spy)
        }
        val result = EcarxDimApi.updateLaneInfo(navi, array)
        // The client SDK normally marshals the array before the binder call returns; this sleep is
        // only for a firmware that reads the getters on a worker thread.
        if (calls.isEmpty()) Thread.sleep(100)
        val captured = synchronized(calls) { calls.toList() }
        return buildString {
            appendLine(
                "updateLaneInfo(" + SPY_LANES + " lanes) -> " +
                    if (result.ok) "accepted" else "failed: " + result.reason,
            )
            if (captured.isEmpty()) {
                appendLine("no getter was read; the platform may ignore lane data outside navigation")
            } else {
                appendLine("interface calls read " + captured.size + " times:")
                captured.forEachIndexed { position, call -> appendLine("  $position: $call") }
            }
        }.trimEnd()
    }

    private fun invocationValue(proxy: Any, method: Method, index: Int): Any? {
        when (method.name) {
            "toString" -> return "ILaneInfoProbe[$index]"
            "hashCode" -> return System.identityHashCode(proxy)
            "describeContents" -> return 0
            "writeToParcel" -> return null
        }
        val type = method.returnType
        return when {
            type == java.lang.Boolean.TYPE -> index % 2 == 0
            type == java.lang.Byte.TYPE -> index.toByte()
            type == java.lang.Short.TYPE -> index.toShort()
            type == java.lang.Integer.TYPE -> index
            type == java.lang.Long.TYPE -> index.toLong()
            type == java.lang.Float.TYPE -> index.toFloat()
            type == java.lang.Double.TYPE -> index.toDouble()
            type == java.lang.Character.TYPE -> '0' + index
            type == String::class.java -> "lane?$index"
            type.isArray -> arrayValue(type.componentType, index)
            else -> null
        }
    }

    private fun arrayValue(component: Class<*>, index: Int): Any {
        val array = ReflectArray.newInstance(component, 1)
        when (component) {
            java.lang.Boolean.TYPE -> ReflectArray.setBoolean(array, 0, index % 2 == 0)
            java.lang.Byte.TYPE -> ReflectArray.setByte(array, 0, (index * 45).toByte())
            java.lang.Short.TYPE -> ReflectArray.setShort(array, 0, (index * 45).toShort())
            java.lang.Integer.TYPE -> ReflectArray.setInt(array, 0, index * 45)
            java.lang.Long.TYPE -> ReflectArray.setLong(array, 0, index * 45L)
            java.lang.Float.TYPE -> ReflectArray.setFloat(array, 0, index * 45f)
            java.lang.Double.TYPE -> ReflectArray.setDouble(array, 0, index * 45.0)
            java.lang.Character.TYPE -> ReflectArray.setChar(array, 0, '0' + index)
            String::class.java -> ReflectArray.set(array, 0, "lane?$index")
            else -> return array
        }
        return array
    }

    private fun render(value: Any?): String {
        if (value == null) return "null"
        if (!value.javaClass.isArray) return value.toString()
        val length = ReflectArray.getLength(value)
        return (0 until length).joinToString(",", "[", "]") {
            ReflectArray.get(value, it)?.toString() ?: "null"
        }
    }

    private fun signature(method: Method): String {
        val flags = mutableListOf<String>()
        if (method.isDefault) flags += "default"
        if (method.isSynthetic) flags += "synthetic"
        if (method.isBridge) flags += "bridge"
        val suffix = if (flags.isEmpty()) "" else " [" + flags.joinToString(",") + "]"
        return method.name + "(" +
            method.parameterTypes.joinToString(",") { it.simpleName } + "): " +
            method.returnType.simpleName + suffix
    }

    private fun describe(error: Throwable): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        return cause.javaClass.simpleName + ": " + cause.message
    }
}
