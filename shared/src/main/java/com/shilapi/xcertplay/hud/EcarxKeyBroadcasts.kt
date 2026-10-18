package com.shilapi.xcertplay.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * The ECARX private steering-wheel key broadcasts, recovered from the reference app's
 * `media$EcarXVoiceKey` / `media$EcarXCalleKey` receivers (see 适配文件/方控与语音助手按键-反编译结论.md).
 *
 * On 领克 / 亿咖通 head units the steering wheel's voice key and call key are delivered as system
 * broadcasts rather than as `KeyEvent`s, so a normal activity never sees them. The reference app subscribes to
 * these two actions and calls `abortBroadcast()` on a match to take ownership away from the factory
 * HMI. DiPlay mirrors that: each [register] returns a receiver that forwards the press to [onPress].
 */
object EcarxKeyBroadcasts {
    private const val ACTION_VOICE = "ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT"
    private const val ACTION_CALL = "ecarx.intent.action.ECARX_KEY_RCALL_EVENT"

    /** Registers the voice-key broadcast; [onPress] runs on the broadcast's thread. */
    fun registerVoice(context: Context, onPress: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_VOICE) return
                abortBroadcast()
                onPress()
            }
        }
        val filter = IntentFilter(ACTION_VOICE).apply { priority = IntentFilter.SYSTEM_HIGH_PRIORITY }
        context.registerReceiver(receiver, filter)
        return receiver
    }

    /** Registers the call-key broadcast; [onPress] runs on the broadcast's thread. */
    fun registerCall(context: Context, onPress: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_CALL) return
                abortBroadcast()
                onPress()
            }
        }
        val filter = IntentFilter(ACTION_CALL).apply { priority = IntentFilter.SYSTEM_HIGH_PRIORITY }
        context.registerReceiver(receiver, filter)
        return receiver
    }
}
