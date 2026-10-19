package com.shilapi.xcertplay.hud

/**
 * The next maneuver exactly as Apple sent it, for the CarPlay outputs that render it.
 *
 * `type` and `drivingSide` are Apple's own RouteGuidanceManeuverType / DrivingSide values, kept
 * unconverted on purpose: each output maps them to its own icon vocabulary, and a vendor that has
 * no recovered code table must not pretend otherwise.
 */
internal data class AppleManeuver(
    val distanceMeters: Int,
    val type: Int,
    val drivingSide: Int,
    val road: String = "",
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    val arrivalEpochSeconds: Long? = null,
    val laneHighlight: Int = -1,
    val lanes: List<Int> = emptyList(),
)

internal enum class RouteGuidanceChange {
    NONE,
    GUIDANCE,
    CLEAR,
}

/**
 * Decodes the iAP2 route-guidance subset (0x5201/0x5202) that every CarPlay output needs.
 *
 * This is vendor-neutral: it only turns Apple's frames into [AppleManeuver], and it is shared by
 * the ECARX cluster output and by the widget-facing glance so both agree on what the next turn is.
 */
internal class RouteGuidanceState(
    private val nanoTime: () -> Long = System::nanoTime,
    private val staleRouteNs: Long = STALE_ROUTE_NS,
    private val emptyListHideNs: Long = EMPTY_LIST_HIDE_NS,
    /**
     * The dashboard overlay keeps the last instruction across a wireless session drop: the iPhone
     * often sends NoRouteSet (0) while the tunnel is tearing down, which is not a real arrival.
     * Arrived (2) still ends the route. The overlay's own stale window retires a truly ended one.
     */
    private val keepAcrossNoRoute: Boolean = false,
) {
    private data class Maneuver(val type: Int, val drivingSide: Int, val afterRoad: String)

    private val maneuvers = mutableMapOf<Int, Maneuver>()
    private var routeActive = false
    private var activeIndex = -1
    private var distanceMeters = 0
    private var currentRoad = ""
    private var arrivalEpochSeconds: Long? = null
    private var remainingSeconds: Long? = null
    private var remainingMeters: Long? = null
    private var laneHighlight = -1
    private var lanes: List<Int> = emptyList()
    private var laneTlvDump = ""
    private var emptyListSinceNs: Long? = null
    private var lastRouteUpdateNs: Long? = null

    fun accept(messageId: Int, payload: ByteArray): RouteGuidanceChange {
        if (!validTlvs(payload)) return RouteGuidanceChange.NONE
        return when (messageId) {
            ROUTE_GUIDANCE_UPDATE -> parseRouteUpdate(payload)
            ROUTE_GUIDANCE_MANEUVER_UPDATE -> parseManeuverUpdate(payload)
            LANE_GUIDANCE_UPDATE -> parseLaneUpdate(payload)
            else -> RouteGuidanceChange.NONE
        }
    }

    /** Next maneuver as Apple sent it, or null when there is no live guidance. */
    fun current(): AppleManeuver? {
        val maneuver = activeManeuver() ?: return null
        return AppleManeuver(
            distanceMeters, maneuver.type, maneuver.drivingSide,
            roadFor(maneuver), remainingSeconds, remainingMeters, arrivalEpochSeconds,
            laneHighlight, lanes,
        )
    }

    fun clear(): Boolean {
        val wasActive = routeActive
        routeActive = false
        activeIndex = -1
        distanceMeters = 0
        currentRoad = ""
        arrivalEpochSeconds = null
        remainingSeconds = null
        remainingMeters = null
        laneHighlight = -1
        lanes = emptyList()
        laneTlvDump = ""
        emptyListSinceNs = null
        lastRouteUpdateNs = null
        maneuvers.clear()
        return wasActive
    }

    private fun activeManeuver(): Maneuver? {
        if (!routeActive || activeIndex < 0) return null
        val updated = lastRouteUpdateNs ?: return null
        if (nanoTime() - updated >= staleRouteNs) return null
        val emptySince = emptyListSinceNs
        if (emptySince != null && nanoTime() - emptySince >= emptyListHideNs) return null
        return maneuvers[activeIndex]
    }

    // The road the driver turns onto is what the next instruction is about; fall back to the current one.
    private fun roadFor(maneuver: Maneuver): String = maneuver.afterRoad.ifEmpty { currentRoad }

    /** Raw lane-guidance state for diagnostics, even when no maneuver is active. */
    fun laneSnapshot(): Pair<Int, List<Int>> = laneHighlight to lanes

    /** All 0x5204 TLVs as type:hex, for matching against the MIB2 lane field model. */
    fun laneTlvDump(): String = laneTlvDump

    private fun parseRouteUpdate(data: ByteArray): RouteGuidanceChange {
        // A teardown NoRouteSet is not fresh guidance. Do not let repeated teardown frames
        // extend the retained instruction's lifetime or replace its road/arrival metadata.
        if (keepAcrossNoRoute) {
            var noRoute = false
            forEachTlv(data) { type, value, valueLength ->
                if (type == 0x01 && valueLength >= 1) noRoute = data[value] == 0.toByte()
            }
            if (noRoute) return RouteGuidanceChange.NONE
        }
        lastRouteUpdateNs = nanoTime()
        var state: Int? = null
        var distance: Int? = null
        var firstManeuver: Int? = null
        var listPresent = false
        forEachTlv(data) { type, value, valueLength ->
            when {
                type == 0x01 && valueLength >= 1 -> state = data[value].toInt() and 0xff
                type == 0x03 -> currentRoad = utf8(data, value, valueLength)
                type == 0x05 && valueLength >= 8 -> arrivalEpochSeconds = u64(data, value).takeIf { it > 0 }
                type == 0x06 && valueLength >= 8 -> remainingSeconds = u64(data, value).takeIf { it >= 0 }
                type == 0x07 && valueLength >= 4 -> remainingMeters = u32(data, value)
                type == 0x0a && valueLength >= 4 -> {
                    distance = u32(data, value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
                type == 0x0d -> {
                    listPresent = true
                    if (valueLength >= 2) firstManeuver = u16(data, value)
                }
            }
        }

        // Only NoRouteSet (0) and Arrived (2) end the route. A wireless handoff often sends
        // NoRouteSet while the session is still coming back — keep the overlay instruction then.
        if (state == 0 || state == 2) {
            return if (clear()) RouteGuidanceChange.CLEAR else RouteGuidanceChange.NONE
        }
        // The iPhone briefly sends an empty current list every few seconds and while rerouting. Keep the last
        // maneuver (and the cached 0x5202 details, which are never resent) and hide it only if the list stays
        // empty; the outputs' 1 s tick clears themselves once current() turns null.
        if (listPresent && firstManeuver == null) {
            if (emptyListSinceNs == null) emptyListSinceNs = nanoTime()
            return RouteGuidanceChange.NONE
        }
        if (firstManeuver != null) emptyListSinceNs = null
        if (state != null) routeActive = true
        if (firstManeuver != null) {
            activeIndex = firstManeuver!!
            routeActive = true
        }
        if (distance != null) distanceMeters = distance!!.coerceAtLeast(0)
        return if (current() != null) RouteGuidanceChange.GUIDANCE else RouteGuidanceChange.NONE
    }

    private fun parseManeuverUpdate(data: ByteArray): RouteGuidanceChange {
        var index: Int? = null
        var type: Int? = null
        var drivingSide = 0
        var afterRoad = ""
        forEachTlv(data) { id, value, valueLength ->
            when {
                id == 0x01 && valueLength >= 2 -> index = u16(data, value)
                id == 0x03 && valueLength >= 1 -> type = data[value].toInt() and 0xff
                id == 0x04 -> afterRoad = utf8(data, value, valueLength)
                id == 0x08 && valueLength >= 1 -> drivingSide = data[value].toInt() and 0xff
            }
        }
        if (index != null && type != null) maneuvers[index!!] = Maneuver(type!!, drivingSide, afterRoad)
        return if (current() != null) RouteGuidanceChange.GUIDANCE else RouteGuidanceChange.NONE
    }

    /** Apple 0x5204 LaneGuidanceInfoUpdate: 0x01 highlighted lane, 0x02 one lane, 0x03 lane array. */
    private fun parseLaneUpdate(data: ByteArray): RouteGuidanceChange {
        var highlight = -1
        val next = mutableListOf<Int>()
        val dump = mutableListOf<String>()
        forEachTlv(data) { id, value, valueLength ->
            dump += "0x%02x:%s".format(id, hex(data, value, valueLength))
            when {
                id == 0x01 && valueLength >= 1 -> highlight = data[value].toInt() and 0xff
                id == 0x02 && valueLength >= 1 -> next += data[value].toInt() and 0xff
                id == 0x03 -> {
                    for (offset in 0 until valueLength) {
                        next += data[value + offset].toInt() and 0xff
                    }
                }
            }
        }
        lanes = next.take(8)
        laneHighlight = if (lanes.isEmpty()) -1 else highlight.coerceIn(-1, lanes.lastIndex)
        laneTlvDump = dump.joinToString(",").take(1024)
        return if (current() != null) RouteGuidanceChange.GUIDANCE else RouteGuidanceChange.NONE
    }

    private fun hex(data: ByteArray, offset: Int, length: Int): String = buildString {
        for (index in 0 until length) append("%02x".format(data[offset + index].toInt() and 0xff))
    }

    private inline fun forEachTlv(data: ByteArray, block: (Int, Int, Int) -> Unit) {
        var offset = 0
        while (offset < data.size) {
            val length = u16(data, offset)
            block(u16(data, offset + 2), offset + TLV_HEADER_BYTES, length - TLV_HEADER_BYTES)
            offset += length
        }
    }

    private fun validTlvs(data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            if (offset + TLV_HEADER_BYTES > data.size) return false
            val length = u16(data, offset)
            if (length < TLV_HEADER_BYTES || length > data.size - offset) return false
            offset += length
        }
        return offset == data.size
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)

    private fun u64(data: ByteArray, offset: Int): Long = (u32(data, offset) shl 32) or u32(data, offset + 4)

    // iAP2 utf8 parameters are NUL-terminated.
    private fun utf8(data: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8).trim()
    }

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
        const val LANE_GUIDANCE_UPDATE = 0x5204
        private const val TLV_HEADER_BYTES = 4
        private const val STALE_ROUTE_NS = 30_000_000_000L
        private const val EMPTY_LIST_HIDE_NS = 3_000_000_000L
    }
}
