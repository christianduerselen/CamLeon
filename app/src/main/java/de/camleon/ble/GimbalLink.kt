package de.camleon.ble

import android.bluetooth.le.ScanFilter
import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connection manager on top of [ChameleonBleClient]: remembers the last connected gimbal and keeps
 * trying to (re)connect to it automatically — the gimbal link is the first thing the user needs,
 * so the app should establish it by itself after launch and after any drop.
 *
 * Strategy per attempt: direct connect to the remembered address (fast when the gimbal advertises),
 * else a short scan; connect to the remembered device if seen, otherwise to the only Gen2 device in
 * range. Repeats with backoff while auto-connect is enabled and the link is not READY.
 */
class GimbalLink(context: Context, private val ble: ChameleonBleClient) {

    data class Remembered(val address: String, val name: String?)

    private val prefs = context.applicationContext.getSharedPreferences("gimbal_link", Context.MODE_PRIVATE)
    private val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    private val _remembered = MutableStateFlow(load())
    val remembered: StateFlow<Remembered?> = _remembered.asStateFlow()
    private val _autoConnect = MutableStateFlow(prefs.getBoolean("auto", true))
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()
    /** Human-readable phase: idle / searching / connecting / ready / waiting */
    private val _phase = MutableStateFlow("idle")
    val phase: StateFlow<String> = _phase.asStateFlow()
    @Volatile var attempts = 0; private set
    /**
     * Set by a manual disconnect: auto-connect stays enabled (persisted preference untouched) but the
     * loop does not reconnect until the user presses "Connect now" or toggles auto-connect, or the app
     * restarts. Not persisted on purpose.
     */
    @Volatile var paused = false; private set

    private fun load(): Remembered? = prefs.getString("address", null)?.let { Remembered(it, prefs.getString("name", null)) }

    fun remember(address: String, name: String?) {
        prefs.edit().putString("address", address).putString("name", name).apply()
        _remembered.value = Remembered(address, name)
    }
    fun forget() { prefs.edit().remove("address").remove("name").apply(); _remembered.value = null }
    fun setAutoConnect(on: Boolean) { prefs.edit().putBoolean("auto", on).apply(); _autoConnect.value = on; if (on) kick() }

    /**
     * User-initiated disconnect: drop the link and hold the auto-connect loop. Then one scan, so the list
     * shows what is in range (the gimbal advertises again within a second — measured) and the user can
     * pick it or another one; nothing is connected automatically while paused.
     */
    fun disconnectManually() {
        paused = true; _phase.value = "disconnected by user"; ble.disconnect()
        scope.launch { delay(500); if (paused && ble.state.value != ChameleonBleClient.ConnState.READY) ble.startScan(12_000) }
    }

    /** Start the supervision loop (call once BLE permissions are granted). Safe to call repeatedly. */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            // persist whatever we end up connected to
            launch { ble.state.collect { s -> if (s == ChameleonBleClient.ConnState.READY) ble.connectedAddress?.let { remember(it, ble.connectedName) } } }
            var backoff = 3_000L
            while (true) {
                if (!_autoConnect.value || paused || ble.state.value == ChameleonBleClient.ConnState.READY || ble.state.value == ChameleonBleClient.ConnState.OFF) {
                    _phase.value = when {
                        ble.state.value == ChameleonBleClient.ConnState.READY -> "ready"
                        ble.state.value == ChameleonBleClient.ConnState.OFF -> "bluetooth off"
                        // paused / auto-connect off without a remembered gimbal: the manual scan the user needs
                        // next is refused by Android while the screen is off, so say that instead
                        _remembered.value == null && !power.isInteractive -> "screen off — turn it on to search for the gimbal"
                        paused -> "disconnected by user (auto-connect paused)"
                        else -> "idle"
                    }
                    backoff = 3_000L; delay(1_500); continue
                }
                if (ble.state.value == ChameleonBleClient.ConnState.CONNECTING || ble.state.value == ChameleonBleClient.ConnState.DISCOVERING) { delay(1_000); continue }
                attempts++
                val ok = attempt()
                if (!ok) {
                    _phase.value = "waiting (${backoff / 1000}s)"
                    delay(backoff); backoff = (backoff * 2).coerceAtMost(30_000L)
                } else backoff = 3_000L
            }
        }
    }

    /** Trigger an immediate attempt (e.g. user pressed "Connect now"). */
    fun kick() { paused = false; kickRequested = true }
    @Volatile private var kickRequested = false

    private suspend fun attempt(): Boolean {
        val rem = _remembered.value
        // 1) direct connect to the remembered address
        if (rem != null) {
            _phase.value = "connecting to ${rem.name ?: rem.address}"
            ble.connect(rem.address)
            if (awaitReady(12_000)) return true
            ble.disconnect()
        }
        // 2) scan and pick. Android silently postpones an UNFILTERED scan while the screen is off
        //    ("Cannot start unfiltered scan in screen-off"): with a remembered gimbal scan filtered by its
        //    address instead (allowed in screen-off), without one the user has to turn the screen on.
        if (rem == null && !power.isInteractive) {
            _phase.value = "screen off — turn it on to search for the gimbal"
            while (!power.isInteractive) { if (paused || !_autoConnect.value) return false; delay(1_000) }   // scan right away once it is on
        }
        _phase.value = "searching for gimbal"
        ble.startScan(8_000, if (power.isInteractive || rem == null) null else listOf(ScanFilter.Builder().setDeviceAddress(rem.address).build()))
        val target = withTimeoutOrNull(8_500) {
            while (true) {
                val found = ble.found.value
                // The Chameleon does not advertise the AE30 service UUID (verified on hardware: name
                // "XbotGo-xxxxxx", no service UUIDs in the advertisement), so also accept a single
                // XbotGo-named device.
                val pick = rem?.let { r -> found.firstOrNull { it.address == r.address } }
                    ?: found.filter { it.hasGen2Service || it.name.startsWith("XbotGo", ignoreCase = true) }.takeIf { it.size == 1 }?.first()
                if (pick != null) return@withTimeoutOrNull pick
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        ble.stopScan()
        if (target == null) { _phase.value = if (ble.found.value.isEmpty()) "no gimbal in range" else "several candidates — pick one manually"; return false }
        _phase.value = "connecting to ${target.name.ifBlank { target.address }}"
        ble.connect(target.address)
        return awaitReady(12_000)
    }

    private suspend fun awaitReady(ms: Long): Boolean =
        withTimeoutOrNull(ms) { ble.state.first { it == ChameleonBleClient.ConnState.READY } } != null

    private companion object { const val TAG = "GimbalLink" }
}
