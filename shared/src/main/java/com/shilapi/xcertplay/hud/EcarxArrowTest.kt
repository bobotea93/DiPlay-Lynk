package com.shilapi.xcertplay.hud

/**
 * The three ECARX turn-arrow overloads, as exercised by the Settings diagnostics.
 *
 * [TURN_ID] is the numeric channel whose code table was never recovered on this head unit, so it
 * is swept over every value. [BITMAP] and [SVG] carry an icon instead of a code, so they are swept
 * over the basic maneuvers in [EcarxArrowShape].
 */
enum class EcarxArrowTestKind {
    TURN_ID,
    BITMAP,
    SVG,
}

/**
 * The basic maneuvers the bitmap and SVG tests push, in the order they are shown.
 *
 * The Apple maneuver type and driving side are the ones [EcarxHudBridge] already maps for the real
 * guidance push, so the test drives the same drawing code the live path uses.
 */
enum class EcarxArrowShape(internal val appleType: Int, internal val drivingSide: Int) {
    STRAIGHT(3, 0),
    SLIGHT_LEFT(13, 0),
    LEFT(1, 0),
    SHARP_LEFT(47, 0),
    SLIGHT_RIGHT(14, 0),
    RIGHT(2, 0),
    SHARP_RIGHT(48, 0),
    U_TURN(4, 0),
    ROUNDABOUT(6, 0),
    DESTINATION(10, 0),
}

/** One pushed step of a running test, so Settings can name what is on the cluster. */
data class EcarxArrowTestStep(
    val kind: EcarxArrowTestKind,
    val index: Int,
    val total: Int,
    /** The numeric turn ID, or -1 outside [EcarxArrowTestKind.TURN_ID]. */
    val turnId: Int = -1,
    /** The maneuver, or null for [EcarxArrowTestKind.TURN_ID]. */
    val shape: EcarxArrowShape? = null,
)
