package com.shilapi.xcertplay.hud

/**
 * The two arrow-icon overloads, as exercised by the Settings diagnostics.
 *
 * The numeric `updateTurnByTurnArrow(int)` channel was never usable on this head unit, so it is
 * not exposed here. [BITMAP] and [SVG] carry an icon instead of a code, so they are swept over the
 * basic maneuvers in [EcarxArrowShape].
 */
enum class EcarxArrowTestKind {
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
    U_TURN_LEFT(4, 0),
    U_TURN_RIGHT(4, 1),
    ROUNDABOUT(6, 0),
    DESTINATION(10, 0),
}

/** One pushed step of a running test, so Settings can name what is on the cluster. */
data class EcarxArrowTestStep(
    val kind: EcarxArrowTestKind,
    val index: Int,
    val total: Int,
    /** The maneuver whose icon was pushed. */
    val shape: EcarxArrowShape,
)
