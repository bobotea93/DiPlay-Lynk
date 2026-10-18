package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Lynk port (fix9): during a CarPlay session, take the head unit's own Bluetooth audio path away
 * from the iPhone — media and calls only, adapter stays on.
 *
 * The iPhone keeps the head unit on its list of audio outputs for as long as the Bluetooth link to
 * it is up, so iOS hands music and calls to the car's A2DP/HFP profiles instead of to CarPlay, and
 * the car's media app answers with an AVRCP `pause` — the reported "music stops the moment it
 * starts" fault. The owner's manual workaround is to turn the phone's Bluetooth off, or to untick
 * "Media audio" on the head unit's paired-device screen; there is no ADB on this head unit, so the
 * app has to do it.
 *
 * `CarBluetoothYielder` (the earlier attempt, rolled back) only called `disconnect()`, which is a
 * one-shot: the head unit reconnects the profile and a throttled re-drop loop follows. This version
 * leads with `setConnectionPolicy(device, FORBIDDEN)`, the same thing the car's own checkbox writes
 * — it is persistent, so the profiles stay down without a fight — and keeps `disconnect()` as the
 * immediate effect plus a throttled re-assert as a belt-and-braces path.
 *
 * The Bluetooth adapter itself is never touched by the default mode, because wireless CarPlay's
 * iAP2 handshake and its reconnects run over an RFCOMM channel of that same adapter; only `hfp` and
 * `a2dp` are profile-level concerns. [adapterEnabled] keeps the older, blunter adapter-off mode for
 * head units whose Bluetooth stack ignores profile policy, and that mode must wake the adapter
 * before any reconnect.
 *
 * Reachability is the open question on this ROM: the earlier attempt logged
 * `a2dp=timeout hfp=timeout` — `getProfileProxy` answered `true` and `onServiceConnected` never
 * arrived. So each profile is acquired through two independent paths and the field log says which
 * one answered:
 *
 *  1. `proxy`  — the public `BluetoothAdapter.getProfileProxy`, then reflection into
 *                `BluetoothA2dp`/`BluetoothHeadset` (`setConnectionPolicy`, `setPriority`,
 *                `disconnect`, `connect`). These are `@SystemApi`, so they are absent from the
 *                public SDK but present on the device; the install is platform-signed, which is
 *                what `BLUETOOTH_PRIVILEGED` needs.
 *  2. `binder` — `ServiceManager` → `IBluetoothA2dp` / `IBluetoothHeadset`, skipping the callback
 *                mechanism entirely. Used when path 1 times out.
 *
 * One line per session — `BT guard link: services a2dp=proxy hfp=binder` — is enough to tell the
 * two apart in the exported log.
 */
public object CarBluetoothProfileGuard {

    private const val TAG = "DiPlay-BT-Yield"
    private const val PREFS = "diplay_bt_profile_guard"
    private const val KEY_LINK_ENABLED = "yield_link"
    private const val KEY_ADAPTER_ENABLED = "yield_adapter"
    private const val KEY_APPLIED = "applied"
    private const val KEY_APPLIED_MODE = "applied_mode"
    private const val KEY_APPLIED_TARGETS = "applied_targets"
    private const val MODE_LINK = "link"
    private const val MODE_ADAPTER = "adapter"

    /** Let the handshake and the first media frames settle before touching the profiles. */
    private const val LINK_DELAY_MILLIS = 5_000L
    private const val ADAPTER_DELAY_MILLIS = 10_000L
    private const val REASSERT_PERIOD_MILLIS = 30_000L
    private const val REDROP_THROTTLE_MILLIS = 3_000L
    private const val PROFILE_TIMEOUT_SECONDS = 5L

    /** `BluetoothProfile.CONNECTION_POLICY_*`; `setPriority`'s `PRIORITY_OFF`/`ON` share the values. */
    private const val POLICY_FORBIDDEN = 0
    private const val POLICY_ALLOWED = 100

    private const val A2DP = BluetoothProfile.A2DP
    private const val HEADSET = BluetoothProfile.HEADSET

    /** `BluetoothProfile.ACTION_CONNECTION_STATE_CHANGED`: the generic (system API) sibling. */
    private const val ACTION_PROFILE_STATE = "android.bluetooth.profile.action.CONNECTION_STATE_CHANGED"

    private val logcat: (String) -> Unit = { Log.i(TAG, it) }
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "diplay-bt-guard").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var session: Session? = null
    private var pending: ScheduledFuture<*>? = null
    private var reassert: ScheduledFuture<*>? = null
    private var watcher: BroadcastReceiver? = null
    private var lastDropAt = 0L
    private var recovered = false

    /** One profile service, whichever way it was reached. `profile == null` means "unreachable". */
    private class Handle(val label: String, val profileId: Int, val source: String, val profile: Any?)

    private class Session(
        val context: Context,
        val adapterMode: Boolean,
        val peerAddress: String?,
        val log: (String) -> Unit,
    ) {
        var handles: List<Handle>? = null
        var targets: List<BluetoothDevice> = emptyList()
    }

    // ------------------------------------------------------------------ settings

    /** Profile-level mode: the default, and the only one that leaves the handshake alone. */
    fun linkEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_LINK_ENABLED, true)

    fun saveLinkEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_LINK_ENABLED, enabled).apply()
    }

    /** Adapter-off mode. It kills the wireless handshake while it holds, so it stays opt-in. */
    fun adapterEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ADAPTER_ENABLED, false)

    fun saveAdapterEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ADAPTER_ENABLED, enabled).apply()
    }

    /** Called when a CarPlay session is live. Returns at once; the drop runs on the guard's thread. */
    fun yieldForSession(context: Context, peerAddress: String?, log: (String) -> Unit = logcat) {
        val app = context.applicationContext
        val adapterMode = adapterEnabled(app)
        if (!adapterMode && !linkEnabled(app)) {
            log("BT guard: both switches off; the head unit keeps its Bluetooth to itself")
            return
        }
        val state = Session(app, adapterMode, peerAddress, log)
        val delay = if (adapterMode) ADAPTER_DELAY_MILLIS else LINK_DELAY_MILLIS
        synchronized(lock) {
            pending?.cancel(false)
            session = state
            pending = worker.schedule({ apply(state) }, delay, TimeUnit.MILLISECONDS)
        }
        log(
            "BT guard armed mode=${if (adapterMode) MODE_ADAPTER else MODE_LINK} delayMs=$delay " +
                "peer=${peerAddress ?: "unknown"}",
        )
    }

    /** Puts the profiles back on the way out. Safe (and silent) when nothing was applied. */
    fun restore(context: Context, log: (String) -> Unit = logcat) {
        val app = context.applicationContext
        val state = synchronized(lock) {
            pending?.cancel(false)
            pending = null
            reassert?.cancel(false)
            reassert = null
            session?.also { session = null }
        }
        unregisterWatcher(app)
        if (state == null && !applied(app)) return
        // Queued on the guard's thread so the decision cannot race a drop that is still in flight.
        worker.execute { restoreNow(app, state, state?.log ?: log) }
    }

    /**
     * A previous process can die while the profiles are forbidden, and the policy persists in the
     * head unit's Bluetooth configuration. Startup calls this once so the next run puts them back.
     */
    fun recoverIfStale(context: Context, log: (String) -> Unit = logcat) {
        val app = context.applicationContext
        synchronized(lock) {
            if (recovered) return
            recovered = true
        }
        if (!applied(app)) return
        log("BT guard: a previous run left the head unit's Bluetooth profiles forbidden; restoring")
        restore(app, log)
    }

    /** A switch moved. Re-applies inside a live session, undoes it when both switches are off. */
    fun preferenceChanged(
        context: Context,
        sessionActive: Boolean,
        peerAddress: String?,
        log: (String) -> Unit = logcat,
    ) {
        val app = context.applicationContext
        if (!linkEnabled(app) && !adapterEnabled(app)) {
            restore(app, log)
            return
        }
        if (!sessionActive) {
            log("BT guard: saved; it applies to the next CarPlay session")
            return
        }
        restore(app, log)
        yieldForSession(app, peerAddress, log)
    }

    // ------------------------------------------------------------------ the drop

    private fun apply(state: Session) {
        val adapter = adapter(state.context)
        if (adapter == null) {
            state.log("BT guard: no Bluetooth adapter on this head unit")
            return
        }
        if (!adapter.isEnabled) {
            state.log("BT guard: head unit Bluetooth is already off; nothing to change")
            return
        }
        if (state.adapterMode) {
            applyAdapterMode(state, adapter)
            return
        }
        val targets = targets(state, adapter)
        state.targets = targets
        if (targets.isEmpty()) {
            state.log("BT guard link: no paired iPhone to drop; the head unit keeps its Bluetooth")
            return
        }
        val handles = acquireHandles(state.context, adapter, state.log)
        state.handles = handles
        state.log("BT guard link: services " + handles.joinToString(" ") { "${it.label}=${it.source}" })
        drop(state, handles, targets, "first drop", verbose = true)
        armReassert(state)
    }

    @Suppress("DEPRECATION")
    private fun applyAdapterMode(state: Session, adapter: BluetoothAdapter) {
        val accepted = try {
            adapter.disable()
        } catch (error: SecurityException) {
            state.log("BT guard adapter: the ROM refused to switch Bluetooth off")
            return
        } catch (error: RuntimeException) {
            state.log("BT guard adapter: switching Bluetooth off failed ${error.javaClass.simpleName}")
            return
        }
        if (accepted) markApplied(state.context, MODE_ADAPTER, emptyList())
        state.log("BT guard adapter: head unit Bluetooth off requested accepted=$accepted")
    }

    /**
     * Re-asserts the forbidden policy for [targets] and disconnects whatever is still up. Runs on
     * the first drop, on every profile state broadcast, and once per [REASSERT_PERIOD_MILLIS] —
     * a ROM that does not deliver the broadcasts still converges through the timer.
     */
    private fun drop(
        state: Session,
        handles: List<Handle>,
        targets: List<BluetoothDevice>,
        note: String,
        verbose: Boolean,
    ) {
        lastDropAt = SystemClock.elapsedRealtime()
        val line = StringBuilder("BT guard link: ").append(note)
        var dropped = 0
        for (device in targets) {
            line.append(' ').append(device.address)
            for (handle in handles) {
                val policy = applyPolicy(handle, device, POLICY_FORBIDDEN)
                val disconnect = callOnDevice(handle.profile, "disconnect", device)
                if (disconnect == "true") dropped++
                if (verbose) {
                    line.append(' ').append(handle.label).append('(').append(handle.source)
                        .append(") policy=").append(policy).append(" disconnect=").append(disconnect)
                }
            }
        }
        if (verbose) {
            markApplied(state.context, MODE_LINK, targets.map { it.address })
            state.log(line.toString())
        } else if (dropped > 0) {
            state.log(line.append(" dropped=").append(dropped).toString())
        }
    }

    private fun reassert(reason: String?) {
        val state = synchronized(lock) { session } ?: return
        if (state.adapterMode) return
        if (SystemClock.elapsedRealtime() - lastDropAt < REDROP_THROTTLE_MILLIS) return
        val adapter = adapter(state.context) ?: return
        if (!adapter.isEnabled) return
        var handles = state.handles
        if (handles == null || handles.all { it.profile == null }) {
            handles = acquireHandles(state.context, adapter, state.log)
            state.handles = handles
            state.log("BT guard link: services " + handles.joinToString(" ") { "${it.label}=${it.source}" })
        }
        val targets = state.targets.ifEmpty { targets(state, adapter).also { state.targets = it } }
        if (targets.isEmpty()) return
        drop(state, handles, targets, reason ?: "re-assert", verbose = reason != null)
    }

    private fun armReassert(state: Session) {
        synchronized(lock) {
            // The session may have ended while the drop was running; restore() then owns the clean-up.
            if (session !== state) return
            if (watcher == null) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        val reason = "profile state changed ${intent.action} state=$state"
                        worker.execute { reassert(reason) }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(ACTION_PROFILE_STATE)
                }
                val registered = runCatching {
                    registerReceiverCompat(state.context, receiver, filter)
                }
                if (registered.isSuccess) {
                    watcher = receiver
                } else {
                    state.log(
                        "BT guard link: profile broadcasts not watched " +
                            (registered.exceptionOrNull()?.javaClass?.simpleName ?: ""),
                    )
                }
            }
            if (reassert == null) {
                reassert = worker.scheduleAtFixedRate(
                    { reassert(null) },
                    REASSERT_PERIOD_MILLIS,
                    REASSERT_PERIOD_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreNow(context: Context, state: Session?, log: (String) -> Unit) {
        val adapter = adapter(context)
        if (adapter != null) state?.handles?.let { closeProxies(adapter, it) }
        if (!applied(context)) {
            // A session that ended inside the arming delay never touched anything.
            log("BT guard: nothing had been applied; the head unit keeps its Bluetooth")
            return
        }
        val mode = appliedMode(context)
        val addresses = storedTargets(context)
        if (adapter == null) {
            clearApplied(context)
            log("BT guard restore: no Bluetooth adapter; forgot the stored state")
            return
        }
        if (mode == MODE_ADAPTER) {
            val accepted = try {
                adapter.isEnabled || adapter.enable()
            } catch (error: RuntimeException) {
                log("BT guard restore: switching Bluetooth back on failed ${error.javaClass.simpleName}")
                clearApplied(context)
                return
            }
            clearApplied(context)
            log("BT guard restore: head unit Bluetooth back on accepted=$accepted")
            return
        }
        val devices = addresses.mapNotNull { remoteDevice(adapter, it) }
        if (devices.isEmpty()) {
            clearApplied(context)
            log("BT guard restore: no devices to release")
            return
        }
        val handles = acquireHandles(context, adapter, log)
        val line = StringBuilder("BT guard restore:")
        for (device in devices) {
            line.append(' ').append(device.address)
            for (handle in handles) {
                val policy = applyPolicy(handle, device, POLICY_ALLOWED)
                val connect = callOnDevice(handle.profile, "connect", device)
                line.append(' ').append(handle.label).append('(').append(handle.source)
                    .append(") policy=").append(policy).append(" connect=").append(connect)
            }
        }
        closeProxies(adapter, handles)
        clearApplied(context)
        log(line.append(" devices=").append(devices.size).toString())
    }

    private fun unregisterWatcher(context: Context) {
        val receiver = synchronized(lock) { watcher.also { watcher = null } } ?: return
        runCatching { context.unregisterReceiver(receiver) }
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiverCompat(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    // ------------------------------------------------------------------ targets

    private fun targets(state: Session, adapter: BluetoothAdapter): List<BluetoothDevice> {
        val bonded = try {
            adapter.bondedDevices.orEmpty()
        } catch (error: RuntimeException) {
            state.log("BT guard link: paired devices not readable ${error.javaClass.simpleName}")
            return emptyList()
        }
        val selected = LinkedHashMap<String, BluetoothDevice>()
        val peer = state.peerAddress?.let { address ->
            bonded.firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?: remoteDevice(adapter, address)
        }
        if (peer != null) selected[peer.address] = peer
        for (device in bonded) {
            val isIPhone = try {
                device.name?.contains("iPhone", ignoreCase = true) == true
            } catch (error: RuntimeException) {
                false
            }
            if (isIPhone) selected.putIfAbsent(device.address, device)
        }
        state.log(
            "BT guard link: bonded=${bonded.size} candidates=${selected.size} " +
                "peerKnown=${peer != null}",
        )
        return selected.values.toList()
    }

    private fun remoteDevice(adapter: BluetoothAdapter, address: String): BluetoothDevice? =
        runCatching { adapter.getRemoteDevice(address) }.getOrNull()

    // ------------------------------------------------------------------ profile reachability

    private fun acquireHandles(context: Context, adapter: BluetoothAdapter, log: (String) -> Unit): List<Handle> =
        listOf(A2DP to "a2dp", HEADSET to "hfp").map { (profileId, label) ->
            val proxy = proxyFor(context, adapter, profileId)
            if (proxy != null) {
                Handle(label, profileId, "proxy", proxy)
            } else {
                val binder = binderFor(profileId, label, log)
                if (binder != null) Handle(label, profileId, "binder", binder)
                else Handle(label, profileId, "none", null)
            }
        }

    /**
     * Path 1. `getProfileProxy` is public API; the callbacks are dispatched by the platform, so the
     * request has to leave from the main thread while this worker waits on the latch.
     */
    private fun proxyFor(context: Context, adapter: BluetoothAdapter, profileId: Int): BluetoothProfile? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<BluetoothProfile?>()
        val gaveUp = AtomicBoolean(false)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                if (gaveUp.get()) {
                    runCatching { adapter.closeProfileProxy(id, proxy) }
                } else {
                    holder.set(proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(id: Int) {
                latch.countDown()
            }
        }
        runCatching {
            main.post {
                val accepted = try {
                    adapter.getProfileProxy(context, listener, profileId)
                } catch (error: RuntimeException) {
                    false
                }
                if (!accepted) latch.countDown()
            }
        }
        val ready = try {
            latch.await(PROFILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!ready) {
            gaveUp.set(true)
            return null
        }
        return holder.get()
    }

    /**
     * Path 2. The profile services are ordinary binder services; asking `ServiceManager` for them
     * skips `getProfileProxy` and its callbacks. Names are listed from the ROM rather than assumed,
     * with the AOSP names as a backstop, so a renamed service still gets a chance.
     */
    private fun binderFor(profileId: Int, label: String, log: (String) -> Unit): Any? {
        val interfaceName = if (profileId == A2DP) "IBluetoothA2dp" else "IBluetoothHeadset"
        return try {
            val manager = Class.forName("android.os.ServiceManager")
            val names = ((manager.getMethod("listServices").invoke(null) as? Array<*>)
                ?.mapNotNull { it as? String } ?: emptyList()) +
                listOf("bluetooth_a2dp", "bluetooth_headset")
            val wanted = names.distinct().filter { name ->
                if (profileId == A2DP) {
                    name.contains("a2dp")
                } else {
                    name.contains("headset") || name.contains("handsfree") || name.contains("hfp")
                }
            }
            val getService = manager.getMethod("getService", String::class.java)
            val stub = Class.forName("android.bluetooth.$interfaceName\$Stub")
            val asInterface = stub.getMethod("asInterface", android.os.IBinder::class.java)
            val found = wanted.firstNotNullOfOrNull { name ->
                val binder = getService.invoke(null, name) as? android.os.IBinder
                    ?: return@firstNotNullOfOrNull null
                runCatching { asInterface.invoke(null, binder) }.getOrNull()
            }
            if (found == null) {
                log("BT guard link: no binder for $label tried=${wanted.joinToString("|")}")
            }
            found
        } catch (error: Throwable) {
            log("BT guard link: no binder for $label ${error.javaClass.simpleName}")
            null
        }
    }

    private fun closeProxies(adapter: BluetoothAdapter, handles: List<Handle>) {
        for (handle in handles) {
            val proxy = handle.profile as? BluetoothProfile ?: continue
            if (handle.source == "proxy") {
                runCatching { adapter.closeProfileProxy(handle.profileId, proxy) }
            }
        }
    }

    // ------------------------------------------------------------------ reflection into the profiles

    /**
     * The policy call, which is what keeps the profile down. `setConnectionPolicy` is the modern
     * name and `setPriority` the pre-Android-9 one; both take the same values, and which one this
     * ROM carries is part of what the returned token reports.
     */
    private fun applyPolicy(handle: Handle, device: BluetoothDevice, value: Int): String {
        val modern = callOnDevice(handle.profile, "setConnectionPolicy", device, value)
        if (modern != "NoSuchMethodException") return modern
        return "setPriority:" + callOnDevice(handle.profile, "setPriority", device, value)
    }

    private fun callOnDevice(target: Any?, name: String, device: BluetoothDevice, value: Int? = null): String {
        if (target == null) return "no-service"
        return try {
            val method = if (value == null) {
                target.javaClass.getMethod(name, BluetoothDevice::class.java)
            } else {
                target.javaClass.getMethod(name, BluetoothDevice::class.java, Integer.TYPE)
            }
            method.isAccessible = true
            val result = if (value == null) method.invoke(target, device) else method.invoke(target, device, value)
            result?.toString() ?: "null"
        } catch (error: Throwable) {
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.cause ?: error
            cause.javaClass.simpleName
        }
    }

    // ------------------------------------------------------------------ state

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun markApplied(context: Context, mode: String, addresses: List<String>) {
        preferences(context).edit()
            .putBoolean(KEY_APPLIED, true)
            .putString(KEY_APPLIED_MODE, mode)
            .putString(KEY_APPLIED_TARGETS, addresses.joinToString(","))
            .apply()
    }

    private fun applied(context: Context): Boolean = preferences(context).getBoolean(KEY_APPLIED, false)

    private fun appliedMode(context: Context): String =
        preferences(context).getString(KEY_APPLIED_MODE, MODE_LINK) ?: MODE_LINK

    private fun storedTargets(context: Context): List<String> =
        preferences(context).getString(KEY_APPLIED_TARGETS, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun clearApplied(context: Context) {
        preferences(context).edit()
            .putBoolean(KEY_APPLIED, false)
            .remove(KEY_APPLIED_MODE)
            .remove(KEY_APPLIED_TARGETS)
            .apply()
    }

    private fun adapter(context: Context): BluetoothAdapter? = try {
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    } catch (error: RuntimeException) {
        Log.w(TAG, "Bluetooth adapter unavailable", error)
        null
    }
}
