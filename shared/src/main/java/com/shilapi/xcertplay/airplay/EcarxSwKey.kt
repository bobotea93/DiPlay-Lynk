package com.shilapi.xcertplay.airplay

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * The ECARX steering-wheel key learning model, mirrored from the reference app's `SWKeyActivity`.
 *
 * The reference app lets the user learn eight steering-wheel actions onto any key the car reports, and stores
 * each as an integer key code under `swKey_<action>` in its own preferences file. DiPlay reuses the
 * same actions and preference keys so a user who already learnt their wheel on the reference app keeps the
 * same mapping, and the learning UI is a drop-in equivalent.
 *
 * Actions (reference order, see SWKeyActivity.z):
 *  voice(语音识别) / prev(上一曲) / next(下一曲) / playpause(播放/暂停) / pause(仅暂停) /
 *  play(仅播放) / answer(接听电话) / hangup(挂断电话)
 *
 * Each action's learnt key maps to a CarPlay press via [CarPlayMediaButton]:
 *  voice -> opensSiri, media actions -> forKeyCode, answer/hangup -> forCallKeyCode.
 * The reference app also has two gates: [mediaButtonEnabled] (swMediaButton) and [callKeyConvert]
 * (swMediaKeyCallConvert), which enable the learnt media keys and the answer/hangup conversion.
 *
 * This class is pure Kotlin (no Android types beyond [KeyEvent] constants and [SharedPreferences]),
 * so the mapping is unit-testable.
 */
object EcarxSwKey {
    /** The eight learnable actions, in the reference app's display order. */
    enum class Action(val prefKey: String, val label: String) {
        VOICE("voice", "语音识别"),
        PREV("prev", "上一曲"),
        NEXT("next", "下一曲"),
        PLAY_PAUSE("playpause", "播放/暂停"),
        PAUSE("pause", "暂停"),
        PLAY("play", "播放"),
        ANSWER("answer", "接听电话"),
        HANGUP("hangup", "挂断电话"),
    }

    private const val PREFS = "diplay_sw_key"
    private const val KEY_MEDIA_BUTTON = "swMediaButton"
    private const val KEY_CALL_CONVERT = "swMediaKeyCallConvert"

    /** The key code a user learnt for [action], or -1 when none (matches the reference app's sentinel). */
    fun learntKeyCode(prefs: SharedPreferences, action: Action): Int =
        prefs.getInt("swKey_${action.prefKey}", -1)

    /** Assigns [keyCode] to [action], clearing any other action that already used it (reference semantics). */
    fun assign(prefs: SharedPreferences, action: Action, keyCode: Int) {
        val editor = prefs.edit()
        Action.entries.filter { it != action && learntKeyCode(prefs, it) == keyCode }
            .forEach { editor.remove("swKey_${it.prefKey}") }
        editor.putInt("swKey_${action.prefKey}", keyCode).apply()
    }

    fun clear(prefs: SharedPreferences, action: Action) {
        prefs.edit().remove("swKey_${action.prefKey}").apply()
    }

    /** The action a learnt [keyCode] already triggers, or null. */
    fun actionFor(prefs: SharedPreferences, keyCode: Int): Action? =
        Action.entries.firstOrNull { learntKeyCode(prefs, it) == keyCode }

    fun mediaButtonEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_MEDIA_BUTTON, true)

    fun setMediaButtonEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_BUTTON, enabled).apply()
    }

    fun callKeyConvert(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_CALL_CONVERT, false)

    fun setCallKeyConvert(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALL_CONVERT, enabled).apply()
    }

    /**
     * Resolves a hardware [keyCode] to the CarPlay action it should trigger, honouring the learnt
     * `swKey_*` table first and the standard ECARX code spaces second. Returns a [Press] that names
     * both the action and the CarPlay report index, or null when the key is not a steering-wheel key.
     */
    fun resolve(prefs: SharedPreferences, keyCode: Int): Press? {
        // Learnt keys win: a user can bind any key to any of the eight actions.
        val learnt = actionFor(prefs, keyCode)
        if (learnt != null) return pressFor(learnt, prefs) ?: return null
        // Otherwise the standard mapping applies (voice, media keys, call keys).
        return when {
            CarPlayMediaButton.opensSiri(keyCode) -> Press(Action.VOICE, siri = true)
            CarPlayMediaButton.forCallKeyCode(keyCode) != null ->
                if (callKeyConvert(prefs)) {
                    val index = CarPlayMediaButton.forCallKeyCode(keyCode)!!
                    Press(if (index == CarPlayMediaButton.ANSWER) Action.ANSWER else Action.HANGUP,
                        callIndex = index)
                } else null
            mediaButtonEnabled(prefs) && CarPlayMediaButton.forKeyCode(keyCode) != null ->
                Press(actionForMedia(CarPlayMediaButton.forKeyCode(keyCode)!!),
                    mediaIndex = CarPlayMediaButton.forKeyCode(keyCode)!!)
            else -> null
        }
    }

    private fun pressFor(action: Action, prefs: SharedPreferences): Press? = when (action) {
        Action.VOICE -> Press(action, siri = true)
        Action.PREV -> Press(action, mediaIndex = CarPlayMediaButton.PREVIOUS)
        Action.NEXT -> Press(action, mediaIndex = CarPlayMediaButton.NEXT)
        Action.PLAY_PAUSE -> Press(action, mediaIndex = CarPlayMediaButton.PLAY_PAUSE)
        Action.PAUSE -> Press(action, mediaIndex = CarPlayMediaButton.PAUSE)
        Action.PLAY -> Press(action, mediaIndex = CarPlayMediaButton.PLAY)
        Action.ANSWER -> Press(action, callIndex = CarPlayMediaButton.ANSWER)
        Action.HANGUP -> Press(action, callIndex = CarPlayMediaButton.HANGUP)
    }

    private fun actionForMedia(index: Int): Action = when (index) {
        CarPlayMediaButton.NEXT -> Action.NEXT
        CarPlayMediaButton.PREVIOUS -> Action.PREV
        else -> Action.PLAY_PAUSE
    }

    /** A resolved steering-wheel press: which action and which CarPlay report index it drives. */
    data class Press(
        val action: Action,
        val siri: Boolean = false,
        val mediaIndex: Int? = null,
        val callIndex: Int? = null,
    ) {
        val isCall get() = callIndex != null
        val isMedia get() = mediaIndex != null
        val isSiri get() = siri
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
