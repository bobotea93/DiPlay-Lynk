package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Publishes CarPlay guidance to an ECARX (亿咖通) instrument cluster through the XUI DIM API.
 *
 * The call sequence is the one the shipped ECARX toolkit uses, recovered from
 * `tools-apktool/smali/s1/n.smali` — a Runnable the toolkit posts every 1000 ms:
 * ```
 * navi.updateNavigationInfo(info); navi.updateTurnByTurnArrow(v++);
 * ```
 * so the field order and the 1 s cadence here are not invented.
 *
 * What is proven vs. what needs calibration on the car:
 *  * **Proven.** `updateNavigationInfo` with the 15-getter `INavigationInfo` payload; the getter
 *    set is exhaustive (`s1/r.smali` implements exactly these); `updateDistanceToDestination`,
 *    `updateDistanceToNextGuidancePoint`, `updateETA`, `updateNextGuidancePointName`,
 *    `updateLaneInfo`, `notifyTurnByTurnStarted/Stopped` take the arguments used below.
 *  * **Needs calibration.** `getNavigationTurnId()` is a bare `int` with no lookup table in either
 *    shipped app — the toolkit fills it from a counter that also feeds `updateTurnByTurnArrow`,
 *    i.e. a demo ramp, not a real code table. The same holds for `getDrivingDirection()`. So the
 *    arrow is only pushed behind [EcarxOutputSettings.turnArrowExperimental], and the two
 *    unverified fields carry documented placeholders instead of a guessed enum.
 *
 * Every ECARX call is reflection-only (see [EcarxDimApi]): a direct reference would turn a
 * missing system class into a `NoClassDefFoundError` on every non-ECARX head unit.
 */
internal object EcarxHudBridge {
    private const val TAG = "DiPlay-ECARX-HUD"

    /** Re-send even when nothing changed, so the cluster never quietly drops the card. */
    private const val KEEPALIVE_TICKS = 5

    /**
     * `getNavigationStatus()` while guidance is live. The toolkit copies its own value through
     * unchanged, so the two states are the only ones recovered; 0 is used for idle on teardown.
     */
    private const val NAVI_STATUS_ACTIVE = 1

    /** Apple's LaneGuidanceInfoUpdate: per-lane direction + highlight state. */
    private const val LANE_GUIDANCE_UPDATE = 0x5204
    private const val NAVI_STATUS_IDLE = 0

    /**
     * `drivingDirection` is deliberately left at 0. The field is a plain `int` in `s1/r`, its
     * source value is the toolkit's own, and no scale was recovered — passing Apple's
     * left/right-hand-traffic flag here would be a guess dressed up as data.
     */
    private const val DRIVING_DIRECTION_UNKNOWN = 0

    /**
     * The projection watch. `setPresentationToDimSwitch` is a **runtime** call, not a saved
     * setting: the cluster service is torn down with the ignition and comes back with it, so a
     * single call at app start is thrown away on a car that is only on accessory power — which is
     * exactly how a head unit boots. Measured on the car (领克 DCY11): with the engine off the
     * cluster is absent and the call does nothing, and nothing re-sent it once the engine started.
     *
     * So the bridge keeps re-asserting the switch: once every [PRESENTATION_FAST_SECONDS] until
     * [PRESENTATION_FAST_ATTEMPTS] tries are spent (the cluster usually shows up as soon as the
     * engine does), then every [PRESENTATION_SLOW_SECONDS] for as long as the user leaves the
     * switch on. Both are cheap `void` binder calls, and the slow phase is what covers "the driver
     * started the car ten minutes after the head unit woke up".
     */
    private const val PRESENTATION_FAST_SECONDS = 2
    private const val PRESENTATION_FAST_ATTEMPTS = 30
    private const val PRESENTATION_SLOW_SECONDS = 10

    private val lock = Any()
    private val route = RouteGuidanceState()
    private var context: Context? = null
    private var available = false
    private var dim: Any? = null
    private var navi: Any? = null
    private var protocol: Any? = null
    private var tickerStarted = false
    private var displayWatchRegistered = false

    /** The saved projection intent, and how many ticks are left before it is re-asserted. */
    private var presentationWanted = false
    private var presentationCountdown = 0
    private var presentationAttempts = 0

    /** Bumped whenever the maneuver changes; the closest honest analogue of the toolkit's counter. */
    private var turnId = 0
    private var lastManeuverKey: String? = null
    private var lastPushed: EcarxDimApi.Guidance? = null
    private var ticksSincePush = 0
    private var guidanceActive = false

    // Lane-guidance probe. The cluster draws every lane bright because we send no lane data yet;
    // one capture of the 0x5204 payload plus the ILaneInfo method list is enough to map it.
    private var laneFrames = 0
    private var laneHex: String? = null
    /** Last maneuver type with no deterministic arrow mapping, for the next report. */
    private var lastUnmappedArrowType: Int? = null
    private var laneMethodNames: String? = null

    /**
     * A screen appearing or disappearing is the closest thing to "the car was just started" that a
     * third-party app can observe, so it cancels the backoff instead of making the cluster wait
     * for the next keepalive. On this head unit the cluster screen is not in `getDisplays()` at all
     * (probe v4), so this is a hint, never the mechanism — the keepalive is.
     */
    private fun laneMethods(): String {
        laneMethodNames?.let { return it }
        val names = runCatching {
            Class.forName(EcarxCapabilities.LANE_INFO).declaredMethods
                .sortedBy { it.name }
                .joinToString(",") { method ->
                    method.name + "(" + method.parameterTypes.joinToString(",") { it.simpleName } +
                        "):" + method.returnType.simpleName
                }
        }.getOrElse { "missing:" + it.javaClass.simpleName }
        laneMethodNames = names
        return names
    }

    private val displayWatch = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = nudgeProjection("display $displayId added")
        override fun onDisplayRemoved(displayId: Int) = nudgeProjection("display $displayId removed")
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    /** Resolves the DIM handles once per process. Safe to call from any thread; idempotent. */
    fun initialize(appContext: Context) = synchronized(lock) {
        if (context != null) return@synchronized
        val app = appContext.applicationContext
        context = app
        available = EcarxCapabilities.available(app)
        if (!available) {
            Log.i(TAG, "DIM interface absent, output disabled: ${EcarxCapabilities.describe(app)}")
            return@synchronized
        }
        // Seed the watch from the saved intent before the first attempt, so a head unit that comes
        // back with the engine already running restores the cluster without the user touching it.
        presentationWanted = EcarxOutputSettings.presentationSwitch(app)
        reclaimLocked()
        watchDisplaysLocked(app)
        Log.i(
            TAG,
            "DIM ready navi=${navi != null} protocol=${protocol != null} " +
                "projection=$presentationWanted ${EcarxCapabilities.describe(app)}",
        )
        // Re-assert the persisted OEM-side switches so a restart restores the cluster state.
        // Only the projection switch: setNaviMode is an unverified call (see NAVI_MODE_UNSET) and
        // is left alone until the user actually asks for it.
        EcarxOutputSettings.naviMode(app)
            .takeIf { it >= 0 }
            ?.let { applyNaviModeLocked(it) }
        if (presentationWanted) requestPresentationLocked()
        startTickerLocked()
    }

    /**
     * (Re)resolves the DIM handles. Retried by the ticker while anything is still missing:
     * `DimInteraction.create()` hands back `null` until the cluster service is up, and on a car
     * that is switched on with the engine off — the state every head unit boots in — that is
     * exactly what happens. Unlike [available], which is a class-presence test and cannot change,
     * these handles can appear later, so the retry is the point.
     */
    private fun reclaimLocked(): Boolean {
        val app = context ?: return false
        if (dim == null) dim = EcarxDimApi.createDimInteraction(app)
        val interaction = dim ?: return false
        if (navi == null) navi = EcarxDimApi.naviInteraction(interaction)
        if (protocol == null) protocol = EcarxDimApi.dimProtocolManager(app)
        return true
    }

    private fun watchDisplaysLocked(app: Context) {
        if (displayWatchRegistered) return
        displayWatchRegistered = runCatching {
            val manager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            manager.registerDisplayListener(displayWatch, Handler(Looper.getMainLooper()))
            true
        }.getOrElse {
            Log.i(TAG, "display watch unavailable: ${it.javaClass.simpleName}")
            false
        }
    }

    private fun nudgeProjection(reason: String) = synchronized(lock) {
        if (!presentationWanted) return@synchronized
        Log.i(TAG, "projection watch: $reason")
        presentationCountdown = 0
    }

    private fun startTickerLocked() {
        if (tickerStarted) return
        tickerStarted = true
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diplay-ecarx-hud").apply { isDaemon = true }
        }.scheduleAtFixedRate(::tick, 1, 1, TimeUnit.SECONDS)
    }

    fun onFrame(frame: Iap2Frame) = synchronized(lock) {
        if (!available) return@synchronized
        if (frame.messageId == LANE_GUIDANCE_UPDATE) {
            laneFrames += 1
            if (laneHex == null) {
                laneHex = frame.payload.take(128).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                Log.i(TAG, "lane guidance frame bytes=${frame.payload.size} head=${laneHex}")
            }
        }
        val change = route.accept(frame.messageId, frame.payload)
        if (frame.messageId == LANE_GUIDANCE_UPDATE) {
            val (highlight, lanes) = route.laneSnapshot()
            Log.i(TAG, "lane guidance parsed highlight=" + highlight + " lanes=" + lanes.joinToString(",") +
                " tlvs=" + route.laneTlvDump())
        }
        when (change) {
            RouteGuidanceChange.GUIDANCE -> pushLocked(force = false)
            RouteGuidanceChange.CLEAR -> if (lastPushed != null) endLocked()
            RouteGuidanceChange.NONE -> Unit
        }
    }

    fun clear() = synchronized(lock) {
        route.clear() // Stop the keepalive from restoring guidance after cleanup.
        if (lastPushed != null) endLocked()
    }

    /** Whether guidance is currently on the cluster. */
    fun isActive(): Boolean = synchronized(lock) { lastPushed != null }

    // ---------------------------------------------------------------- OEM switches

    /**
     * The 仪表投屏 switch. Mirrors the centre screen onto the cluster; both shipped ECARX apps
     * toggle it with `setPresentationToDimSwitch(1, "", "", show)`.
     *
     * Turning it on records the intent and arms the watch. It does **not** report whether the
     * cluster changed, because nothing can: the platform call is `void`, unchecked, and returns the
     * same thing whether or not the cluster is even powered. [EcarxResult.ok] here means "the watch
     * owns delivery now"; the switch reports a failure only when the head unit has no DIM
     * interface at all, which is the one case where retrying is pointless.
     */
    fun applyPresentation(show: Boolean): EcarxDimApi.EcarxResult = synchronized(lock) {
        val app = context ?: return EcarxDimApi.EcarxResult.failed("DIM not initialized")
        EcarxOutputSettings.setPresentationSwitch(app, show)
        presentationWanted = show
        if (!show) {
            presentationAttempts = 0
            presentationCountdown = 0
            return applyPresentationLocked(false)
        }
        if (!available) return EcarxDimApi.EcarxResult.failed("no DIM interface on this head unit")
        requestPresentationLocked()
        EcarxDimApi.EcarxResult.ok("projection watch armed")
    }

    /** One attempt now and a fresh fast phase, so a manual toggle is never throttled by the watch. */
    private fun requestPresentationLocked(): EcarxDimApi.EcarxResult {
        val result = applyPresentationLocked(true)
        presentationAttempts = 1
        presentationCountdown = PRESENTATION_FAST_SECONDS - 1
        Log.i(TAG, "projection armed; first attempt ${result.reason}")
        return result
    }

    private fun applyPresentationLocked(show: Boolean): EcarxDimApi.EcarxResult {
        val interaction = dim ?: return EcarxDimApi.EcarxResult.failed("DimInteraction missing")
        val result = EcarxDimApi.setPresentationToDimSwitch(interaction, show)
        if (!result.ok) Log.w(TAG, "presentation switch failed: ${result.reason}")
        else Log.i(TAG, "presentation switch -> $show")
        return result
    }

    /** `DIMProtocolManager.setNaviMode` — makes the cluster prefer an external navigation source. */
    fun applyNaviMode(mode: Int): EcarxDimApi.EcarxResult = synchronized(lock) {
        val app = context ?: return EcarxDimApi.EcarxResult.failed("DIM not initialized")
        EcarxOutputSettings.setNaviMode(app, mode)
        applyNaviModeLocked(mode)
    }

    private fun applyNaviModeLocked(mode: Int): EcarxDimApi.EcarxResult {
        if (mode < 0) return EcarxDimApi.EcarxResult.failed("navi mode never requested")
        val manager = protocol ?: return EcarxDimApi.EcarxResult.failed("DIMProtocolManager missing")
        val result = EcarxDimApi.setNaviMode(manager, mode)
        if (!result.ok) Log.w(TAG, "setNaviMode($mode) failed: ${result.reason}")
        else Log.i(TAG, "cluster navi mode -> $mode")
        return result
    }

    // ---------------------------------------------------------------- push path

    private fun tick() = synchronized(lock) {
        if (!available) return@synchronized
        // The cluster service is not up when the head unit is only on accessory power, so keep
        // asking for the handles as well as for the projection itself.
        if (dim == null || navi == null) reclaimLocked()
        watchProjectionLocked()
        // Guidance can expire without a frame (a list that stays empty), so check every second.
        if (lastPushed != null && route.current() == null) endLocked()
        else if (++ticksSincePush >= KEEPALIVE_TICKS) pushLocked(force = true)
    }

    /**
     * Re-asserts the projection switch until the cluster is up, then keeps it alive.
     *
     * Nothing on this platform reports that the projection took: `getShowPresentationOption()` is
     * the only read-back and it is `-1` on this head unit even while the cluster is mirroring
     * (probe v3/v4, nine reads), so a read-back loop would immediately give up. Re-sending the
     * switch is idempotent and harmless, which is why the watch is unconditional instead.
     */
    private fun watchProjectionLocked() {
        if (!presentationWanted) return
        if (presentationCountdown > 0) {
            presentationCountdown -= 1
            return
        }
        val fast = presentationAttempts < PRESENTATION_FAST_ATTEMPTS
        val interval = if (fast) PRESENTATION_FAST_SECONDS else PRESENTATION_SLOW_SECONDS
        // The ticker runs once a second, so the countdown is a plain seconds counter.
        presentationCountdown = interval - 1
        val result = applyPresentationLocked(true)
        presentationAttempts += 1
        // One line at the start of each phase and then once a minute, not once a second.
        if (presentationAttempts == 1 || !fast && presentationAttempts % 6 == 0) {
            Log.i(TAG, "projection re-asserted #$presentationAttempts every ${interval}s (${result.reason})")
        }
    }

    private fun pushLocked(force: Boolean) {
        val app = context ?: return
        if (!EcarxOutputSettings.enabled(app)) {
            if (lastPushed != null) endLocked()
            return
        }
        val naviRef = navi ?: return
        val maneuver = route.current()
        if (maneuver == null) {
            if (lastPushed != null) endLocked()
            return
        }
        val guidance = guidanceFor(maneuver)
        if (!force && guidance == lastPushed) return

        // Order matches the toolkit: the aggregate payload first, then the discrete fields.
        val result = EcarxDimApi.updateNavigationInfo(naviRef, guidance)
        if (!result.ok) {
            Log.w(TAG, "updateNavigationInfo failed: ${result.reason}")
            return
        }
        val firstFrame = lastPushed == null
        if (firstFrame) EcarxDimApi.notifyTurnByTurnStarted(naviRef)
        EcarxDimApi.updateDistanceToNextGuidancePoint(naviRef, maneuver.distanceMeters.toLong())
        maneuver.remainingMeters?.let { EcarxDimApi.updateDistanceToDestination(naviRef, it) }
        maneuver.arrivalEpochSeconds?.let { EcarxDimApi.updateEta(naviRef, it) }
        maneuver.road.takeIf { it.isNotEmpty() }
            ?.let { EcarxDimApi.updateNextGuidancePointName(naviRef, it) }
        if (EcarxOutputSettings.turnArrowExperimental(app)) {
            // The int overload has no recovered code table, so draw the arrow locally and push it
            // through the (Bitmap) overload instead — the one code-table-free channel the device
            // exposes (see 亿咖通DIM-真机契约转储.md).
            //
            // 路口箭头优先，缺省用导航箭头：only a drawn junction arrow is pushed. When the maneuver
            // has no mapped shape, nothing is written here, so the cluster keeps the navigation
            // arrow it already derives from updateNavigationInfo instead of a counter value that
            // has no meaning on this firmware.
            val bitmap = turnArrowBitmap(maneuver.type, maneuver.drivingSide)
            if (bitmap != null) EcarxDimApi.updateTurnByTurnArrow(naviRef, bitmap)
        }

        lastPushed = guidance
        ticksSincePush = 0
        if (!guidanceActive) {
            guidanceActive = true
            Log.i(TAG, "DIM guidance started turn=${guidance.turnId} road=\"${guidance.nextGuidancePointName}\"")
        }
    }

    private fun endLocked() {
        val naviRef = navi
        if (naviRef != null) {
            EcarxDimApi.notifyTurnByTurnStopped(naviRef)
            if (context?.let(EcarxOutputSettings::turnArrowExperimental) == true) {
                EcarxDimApi.updateTurnByTurnArrow(naviRef, 0)
            }
            EcarxDimApi.updateDistanceToNextGuidancePoint(naviRef, 0)
            EcarxDimApi.updateNextGuidancePointName(naviRef, "")
            EcarxDimApi.updateLaneInfoEmpty(naviRef)
        }
        lastPushed = null
        lastManeuverKey = null
        ticksSincePush = 0
        if (guidanceActive) {
            guidanceActive = false
            Log.i(TAG, "DIM guidance ended")
        }
    }

    /**
     * One [EcarxDimApi.Guidance] per maneuver. Only distance, destination distance, ETA, the road
     * name and the live/idle status are recovered data; `turnId` and `drivingDirection` are
     * documented placeholders (see the class comment).
     */
    private fun guidanceFor(maneuver: AppleManeuver): EcarxDimApi.Guidance {
        val key = "${maneuver.type}|${maneuver.drivingSide}|${maneuver.road}"
        if (key != lastManeuverKey) {
            lastManeuverKey = key
            turnId += 1
        }
        return EcarxDimApi.Guidance(
            turnId = turnId,
            distanceToNextMeters = maneuver.distanceMeters.toLong(),
            distanceToDestinationMeters = maneuver.remainingMeters ?: -1L,
            etaEpochSeconds = maneuver.arrivalEpochSeconds ?: -1L,
            drivingDirection = DRIVING_DIRECTION_UNKNOWN,
            status = NAVI_STATUS_ACTIVE,
            muteState = 0,
            dayNightMode = dayNightMode(),
            nextGuidancePointName = maneuver.road,
        )
    }

    /** `getDayNightMode()` is 0/1 in `s1/r`; the head unit's own day/night split drives the choice. */
    private fun dayNightMode(): Int {
        val app = context ?: return 0
        val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) 1 else 0
    }

    /**
     * Draws the next-turn arrow locally and returns it as a bitmap, so the cluster gets a real icon
     * without any recovered `turnId` code table. `type`/`drivingSide` are Apple's
     * `RouteGuidanceManeuverType`/`DrivingSide` values, mapped with the same grouping DiPlay's own
     * dashboard widget uses (see `NavigationWidget.arrow`).
     *
     * Returns null for an unrecognised maneuver so the caller falls back to the int overload.
     */
    private fun turnArrowBitmap(type: Int, drivingSide: Int): android.graphics.Bitmap? {
        val kind = when (type) {
            1, 20 -> ArrowKind.LEFT
            2, 21 -> ArrowKind.RIGHT
            47 -> ArrowKind.SHARP_LEFT
            48 -> ArrowKind.SHARP_RIGHT
            13, 22, 49, 52 -> ArrowKind.SLIGHT_LEFT
            14, 23, 50, 53 -> ArrowKind.SLIGHT_RIGHT
            4, 18, 19, 26 -> if (drivingSide == 1) ArrowKind.U_TURN_RIGHT else ArrowKind.U_TURN_LEFT
            6, 7, in 28..46 -> ArrowKind.ROUNDABOUT
            10, 12, 24, 25, 27 -> ArrowKind.DESTINATION
            3, 5, 8, 9, 11, 51 -> ArrowKind.STRAIGHT
            else -> {
                if (type != lastUnmappedArrowType) {
                    lastUnmappedArrowType = type
                    Log.i(TAG, "turn arrow unmapped type=" + type + " side=" + drivingSide)
                }
                return null
            }
        }
        val size = 128
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val center = size / 2f
        val r = size * 0.34f
        canvas.drawPath(arrowPath(kind, center, r), paint)
        return bitmap
    }

    private enum class ArrowKind {
        LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
        U_TURN_LEFT, U_TURN_RIGHT, ROUNDABOUT, DESTINATION, STRAIGHT,
    }

    /** Builds the arrow outline for [kind] around [center], radius [r]. */
    private fun arrowPath(kind: ArrowKind, center: Float, r: Float): android.graphics.Path {
        val path = android.graphics.Path()
        val c = center
        when (kind) {
            ArrowKind.LEFT -> {
                path.moveTo(c + r, c)
                path.lineTo(c - r, c)
                path.moveTo(c - r, c)
                path.lineTo(c - r + r * 0.4f, c - r * 0.5f)
                path.moveTo(c - r, c)
                path.lineTo(c - r + r * 0.4f, c + r * 0.5f)
            }
            ArrowKind.RIGHT -> {
                path.moveTo(c - r, c)
                path.lineTo(c + r, c)
                path.moveTo(c + r, c)
                path.lineTo(c + r - r * 0.4f, c - r * 0.5f)
                path.moveTo(c + r, c)
                path.lineTo(c + r - r * 0.4f, c + r * 0.5f)
            }
            ArrowKind.SHARP_LEFT -> {
                path.moveTo(c + r, c - r * 0.5f)
                path.lineTo(c - r * 0.6f, c)
                path.lineTo(c + r, c + r * 0.5f)
                path.moveTo(c + r, c - r * 0.5f)
                path.lineTo(c + r * 0.2f, c - r * 0.5f)
                path.moveTo(c + r, c + r * 0.5f)
                path.lineTo(c + r * 0.2f, c + r * 0.5f)
            }
            ArrowKind.SHARP_RIGHT -> {
                path.moveTo(c - r, c - r * 0.5f)
                path.lineTo(c + r * 0.6f, c)
                path.lineTo(c - r, c + r * 0.5f)
                path.moveTo(c - r, c - r * 0.5f)
                path.lineTo(c - r * 0.2f, c - r * 0.5f)
                path.moveTo(c - r, c + r * 0.5f)
                path.lineTo(c - r * 0.2f, c + r * 0.5f)
            }
            ArrowKind.SLIGHT_LEFT -> {
                path.moveTo(c + r, c - r * 0.35f)
                path.lineTo(c - r * 0.4f, c)
                path.lineTo(c + r, c + r * 0.35f)
                path.moveTo(c + r, c - r * 0.35f)
                path.lineTo(c + r * 0.35f, c - r * 0.15f)
                path.moveTo(c + r, c + r * 0.35f)
                path.lineTo(c + r * 0.35f, c + r * 0.15f)
            }
            ArrowKind.SLIGHT_RIGHT -> {
                path.moveTo(c - r, c - r * 0.35f)
                path.lineTo(c + r * 0.4f, c)
                path.lineTo(c - r, c + r * 0.35f)
                path.moveTo(c - r, c - r * 0.35f)
                path.lineTo(c - r * 0.35f, c - r * 0.15f)
                path.moveTo(c - r, c + r * 0.35f)
                path.lineTo(c - r * 0.35f, c + r * 0.15f)
            }
            ArrowKind.U_TURN_LEFT -> {
                path.moveTo(c - r * 0.3f, c + r * 0.7f)
                path.lineTo(c - r * 0.3f, c - r * 0.5f)
                path.arcTo(c - r * 0.8f, c - r * 0.5f, c + r * 0.2f, c + r * 0.5f, 90f, 180f, false)
                path.lineTo(c + r * 0.2f, c - r * 0.5f)
                path.moveTo(c + r * 0.2f, c - r * 0.5f)
                path.lineTo(c + r * 0.5f, c - r * 0.8f)
            }
            ArrowKind.U_TURN_RIGHT -> {
                path.moveTo(c + r * 0.3f, c + r * 0.7f)
                path.lineTo(c + r * 0.3f, c - r * 0.5f)
                path.arcTo(c - r * 0.2f, c - r * 0.5f, c + r * 0.8f, c + r * 0.5f, -90f, -180f, false)
                path.lineTo(c - r * 0.2f, c - r * 0.5f)
                path.moveTo(c - r * 0.2f, c - r * 0.5f)
                path.lineTo(c - r * 0.5f, c - r * 0.8f)
            }
            ArrowKind.ROUNDABOUT -> {
                path.addCircle(c, c, r * 0.55f, android.graphics.Path.Direction.CW)
                path.moveTo(c, c - r)
                path.lineTo(c, c - r * 0.3f)
            }
            ArrowKind.DESTINATION -> {
                path.addCircle(c, c, r * 0.35f, android.graphics.Path.Direction.CW)
                path.moveTo(c, c - r * 0.35f)
                path.lineTo(c, c - r * 0.6f)
            }
            ArrowKind.STRAIGHT -> {
                path.moveTo(c, c + r)
                path.lineTo(c, c - r)
                path.moveTo(c, c - r)
                path.lineTo(c - r * 0.45f, c - r * 0.6f)
                path.moveTo(c, c - r)
                path.lineTo(c + r * 0.45f, c - r * 0.6f)
            }
        }
        return path
    }

    /** One-line state for the settings diagnostics block. */
    fun diagnostics(context: Context): String = buildString {
        append("ecarx ").append(EcarxCapabilities.describe(context))
        append(" enabled=").append(EcarxOutputSettings.enabled(context))
        append(" presentation=").append(EcarxOutputSettings.presentationSwitch(context))
        append(" naviMode=").append(
            EcarxOutputSettings.naviMode(context).takeIf { it >= 0 } ?: "unset",
        )
        append(" arrowExperimental=").append(EcarxOutputSettings.turnArrowExperimental(context))
        synchronized(lock) {
            append(" wired=").append(available)
            append(" dim=").append(dim != null)
            append(" navi=").append(navi != null)
            append(" protocol=").append(protocol != null)
            append(" projectionWanted=").append(presentationWanted)
            append(" projectionAttempts=").append(presentationAttempts)
            append(" turnId=").append(turnId)
            append(" active=").append(lastPushed != null)
            append(" status=").append(if (lastPushed != null) NAVI_STATUS_ACTIVE else NAVI_STATUS_IDLE)
            append(" laneFrames=").append(laneFrames)
            append(" laneBytes=").append(laneHex?.length?.div(2) ?: 0)
            append(" laneHex=").append(laneHex ?: "none")
            append(" laneMethods=").append(laneMethods())
            append(" lastUnmappedArrowType=").append(lastUnmappedArrowType ?: "none")
            val lane = route.laneSnapshot()
            append(" laneParsedHighlight=").append(lane.first)
            append(" laneParsedValues=").append(lane.second.joinToString(","))
            append(" laneTlvDump=").append(route.laneTlvDump())
            // The only read-back the API offers. Uninterpreted on purpose — it stays -1 while the
            // cluster mirrors (probe v3/v4), so it is printed for correlation, never acted on.
            dim?.let { append(" showPresentationOption=").append(EcarxDimApi.showPresentationOption(it)) }
        }
    }
}
