package com.shilapi.xcertplay.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log

/**
 * ECARX（亿咖通）集成入口。
 *
 * 这是一个**可选、可失败**的接线层：所有探测都在 `runCatching` 里，非 ECARX 车机上
 * 每个探针都会安静失败，不影响主流程。真正的平台能力在 [EcarxCapabilities] /
 * [EcarxKeyBroadcasts] / [EcarxSwKeyIntercept] 里，这里只负责安装与释放。
 *
 * 移植自 `jiopia/DiPlay-ECARX`（同一台领克 05 / DCY11）。
 */
object EcarxIntegration {
    private const val TAG = "EcarxIntegration"

    private var installed = false
    private var voiceReceiver: BroadcastReceiver? = null
    private var callReceiver: BroadcastReceiver? = null
    private var swKeyHandle: EcarxSwKeyIntercept.Handle? = null

    /** 无论成功与否都不抛异常；返回是否安装成功（至少有一项注册成功）。 */
    @JvmStatic
    fun install(context: Context): Boolean {
        if (installed) return true
        val app = context.applicationContext
        currentContext = app

        Log.i(TAG, EcarxCapabilities.describe(app))
        if (!EcarxCapabilities.available(app)) {
            Log.i(TAG, "非 ECARX 车机，跳过安装")
            return false
        }

        var ok = false

        runCatching {
            voiceReceiver = EcarxKeyBroadcasts.registerVoice(app) {
                Log.i(TAG, "方控语音键按下")
            }
            ok = true
        }.onFailure { Log.w(TAG, "注册语音键广播失败", it) }

        runCatching {
            callReceiver = EcarxKeyBroadcasts.registerCall(app) {
                Log.i(TAG, "方控通话键按下")
            }
            ok = true
        }.onFailure { Log.w(TAG, "注册通话键广播失败", it) }

        runCatching {
            swKeyHandle = EcarxSwKeyIntercept.register(app, object : EcarxSwKeyIntercept.Listener {
                override fun onKeyPressed(keyCode: Int): Boolean {
                    // 默认放行；后续按学到的 keyCode 表接管
                    return false
                }

                override fun onKeyReleased(keyCode: Int): Boolean = false
            })
            if (swKeyHandle != null) ok = true
        }.onFailure { Log.w(TAG, "注册方控按键拦截失败", it) }

        installed = true
        Log.i(TAG, "安装完成 ok=$ok")
        return ok
    }

    /** 释放所有注册；可重复调用。 */
    @JvmStatic
    fun uninstall() {
        if (!installed) return
        installed = false

        voiceReceiver?.let { r -> runCatching { currentContext?.unregisterReceiver(r) } }
        callReceiver?.let { r -> runCatching { currentContext?.unregisterReceiver(r) } }
        voiceReceiver = null
        callReceiver = null

        runCatching { swKeyHandle?.release() }
        swKeyHandle = null
    }

    /** 应用级 Context，仅用于 [uninstall] 反注册广播。 */
    private var currentContext: Context? = null
}
