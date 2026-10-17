package de.camleon

import android.content.Context
import android.util.Log
import de.camleon.ble.ChameleonBleClient
import de.camleon.ble.ChameleonFrame
import de.camleon.ble.GimbalCommands
import de.camleon.ble.GimbalLink
import de.camleon.ble.ProtocolLog
import de.camleon.preview.WebRtcCamera
import de.camleon.preview.WebRtcVideoSource
import de.camleon.stream.CaptureSettings
import de.camleon.stream.MediaPipeline
import de.camleon.stream.TrackingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for the capture instance, shared by the on-device UI and the browser
 * control API. The user-facing model is three independent switches — **Tracking**, **Streaming**,
 * **Recording** — each with its own configuration, plus shared video settings, plus direct gimbal
 * control (manual pan/tilt) that doubles as *tracking override* while tracking is on.
 *
 * Tracking runs **onboard the Chameleon** (its own camera + SDK); the phone only selects the mode,
 * sends parameters and switches the SDK on or off.
 */
object CaptureSession {

    @Volatile private var initialized = false
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var media: MediaPipeline; private set
    lateinit var ble: ChameleonBleClient; private set
    lateinit var link: GimbalLink; private set
    lateinit var camera: WebRtcCamera; private set

    private val _settings = MutableStateFlow(CaptureSettings())
    val settings: StateFlow<CaptureSettings> = _settings.asStateFlow()

    private val _tracking = MutableStateFlow(false)
    /** Desired tracking state; effective (SDKStart) only with a connected gimbal. */
    val tracking: StateFlow<Boolean> = _tracking.asStateFlow()
    @Volatile var trackingNote: String? = null; private set
    /** What the gimbal last acknowledged in a 0x46 frame: mode, capture, track. */
    @Volatile var gimbalAck: Triple<Int, Int, Int>? = null; private set

    // ---- gimbal telemetry (from 0x42 status frames) ----
    private val _gimbalYaw = MutableStateFlow<Double?>(null)
    val gimbalYaw: StateFlow<Double?> = _gimbalYaw.asStateFlow()
    private val _gimbalPitch = MutableStateFlow<Double?>(null)
    val gimbalPitch: StateFlow<Double?> = _gimbalPitch.asStateFlow()
    @Volatile var gimbalBattery: Int? = null; private set
    @Volatile var gimbalPowered: Boolean? = null; private set
    @Volatile var gimbalNote: String? = null; private set
    @Volatile var manualYaw = 0; private set
    @Volatile var manualPitch = 0; private set
    /** Current commanded manual motion (yaw %, pitch %) for the UIs' pad centre: (0,0) = idle. */
    private val _motion = MutableStateFlow(0 to 0)
    val motion: StateFlow<Pair<Int, Int>> = _motion.asStateFlow()
    private var moveJob: Job? = null

    private var autoStartDone = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        camera = WebRtcCamera(appContext)
        // Only offer what this phone's camera can really deliver; a saved quality that is not
        // supported here (e.g. after moving to another phone) falls back to the best supported one.
        _settings.value = CaptureSettings.load(appContext).let { it.copy(quality = coerceQuality(it.quality)) }
            .also { CaptureSettings.save(appContext, it) }
        media = MediaPipeline(appContext, WebRtcVideoSource(camera)).also { it.updateSettings(_settings.value) }
        ble = ChameleonBleClient(appContext).also { it.onFrame = ::onGimbalFrame }
        link = GimbalLink(appContext, ble)
        // Whenever the link becomes READY: send the connect handshake, turn on angle reporting
        // and preselect the tracking mode. On loss: reset telemetry.
        scope.launch {
            var was = false
            ble.state.collect { s ->
                val ready = s == ChameleonBleClient.ConnState.READY
                if (ready && !was) onGimbalReady()
                if (!ready && was) {
                    _gimbalYaw.value = null; _gimbalPitch.value = null; gimbalAck = null; moveJob?.cancel()
                    primed = false; priming = false; primingJob?.cancel(); primingJob = null; gimbalTrackingActive = false
                }
                was = ready
            }
        }
        initialized = true
    }

    private suspend fun onGimbalReady() {
        delay(300)
        GimbalCommands.handshake().forEach { (name, f) -> ble.sendRaw(f, name); delay(120) }
        ble.sendRaw(GimbalCommands.angleReporting(true), "angleReporting(on)"); delay(120)
        ble.sendRaw(GimbalCommands.chooseMode(modeId()), "chooseMode(${modeId()})")
        gimbalNote = null
        delay(300)
        ensurePrimed()
        // "Start tracking automatically at app start": applied once per process, on the first link.
        if (_settings.value.trackingAutoStart && !trackingAutoDone) {
            trackingAutoDone = true
            delay(800)
            Log.i(TAG, "auto-start tracking"); setTracking(true)
        }
    }
    private var trackingAutoDone = false

    private fun modeId(): Int = _settings.value.tracking.modeId()

    fun ensureCameraStarted(): Boolean = camera.start()

    @Synchronized
    fun onReady() {
        link.start()
        if (autoStartDone) return
        autoStartDone = true
        val s = _settings.value
        if (s.streamAutoStart && s.isUrlValid()) { Log.i(TAG, "auto-start streaming"); media.startStreaming(s) }
        if (s.recordAutoStart) { Log.i(TAG, "auto-start recording"); media.startRecording(s) }
    }

    // ---- settings ----------------------------------------------------------------------------

    /** Quality presets whose frame rate the camera offers at that resolution (unknown camera → all). */
    fun supportedQualities(): List<CaptureSettings.Quality> {
        val list = CaptureSettings.Quality.entries.filter { q ->
            val max = runCatching { camera.maxFpsFor(q.width, q.height) }.getOrDefault(0)
            max == 0 || q.fps <= max
        }
        return list.ifEmpty { CaptureSettings.Quality.entries }
    }

    private fun coerceQuality(q: CaptureSettings.Quality): CaptureSettings.Quality {
        val ok = supportedQualities()
        return if (q in ok) q else ok.lastOrNull { it.width <= q.width && it.height <= q.height } ?: ok.last()
    }

    /**
     * Settings are grouped by function and each group is LOCKED while its function is active:
     *  - tracking  (mode, rotation angle, acceleration, auto-start) while tracking runs — the gimbal
     *    takes 0x72 parameters only before SDK start (no ack/log for a live 0x72),
     *  - streaming (RTMP URL, auto-start) while streaming,
     *  - recording (auto-start) while recording,
     *  - video     (quality, audio) while streaming OR recording — the encoder cannot be re-prepared mid-run.
     * Locked groups keep their old values no matter where the change comes from (phone, web, API).
     */
    data class Locks(val tracking: Boolean, val streaming: Boolean, val recording: Boolean) {
        val video get() = streaming || recording
        fun toJson() = JSONObject().put("tracking", tracking).put("streaming", streaming).put("recording", recording).put("video", video)
    }
    fun locks(): Locks = Locks(
        tracking = tracking.value || gimbalTrackingActive,
        streaming = media.isStreaming() || media.stream.value is MediaPipeline.StreamState.Connecting,
        recording = media.isRecording(),
    )
    fun trackingParamsLocked(): Boolean = locks().tracking

    fun updateSettings(new0: CaptureSettings) {
        val old = _settings.value
        val lk = locks()
        val new = new0.copy(
            quality = if (lk.video) old.quality else coerceQuality(new0.quality),
            audioEnabled = if (lk.video) old.audioEnabled else new0.audioEnabled,
            rtmpUrl = if (lk.streaming) old.rtmpUrl else new0.rtmpUrl,
            streamAutoStart = if (lk.streaming) old.streamAutoStart else new0.streamAutoStart,
            recordAutoStart = if (lk.recording) old.recordAutoStart else new0.recordAutoStart,
            trackingAutoStart = if (lk.tracking) old.trackingAutoStart else new0.trackingAutoStart,
            tracking = if (lk.tracking) old.tracking else new0.tracking,
        )
        val modeChanged = new.tracking.modeId() != old.tracking.modeId()
        _settings.value = new
        CaptureSettings.save(appContext, new)
        media.updateSettings(new)
        if (modeChanged && gimbalReady()) ble.sendRaw(GimbalCommands.chooseMode(modeId()), "chooseMode(${modeId()})")
        // Tracking parameters are locked while the SDK runs (see trackingParamsLocked); they take effect at the next tracking start.
    }
    fun updateSettings(params: Map<String, String?>) = updateSettings(_settings.value.applyParams(params))

    // ---- the three switches --------------------------------------------------------------------

    /**
     * Orderly teardown before the process ends ([AppQuit]): stop tracking (the gimbal drives back to its
     * zero), finish the recording (MP4 index written), stop the stream, drop the gimbal link.
     * [done] runs on a background thread once everything is stopped.
     */
    fun shutdown(done: () -> Unit) {
        scope.launch {
            runCatching {
                homeJob?.cancel(); moveJob?.cancel(); trackingJob?.cancel()
                if (gimbalReady() && (_tracking.value || gimbalTrackingActive)) { setTracking(false); delay(400) }
                else if (gimbalReady() && (manualYaw != 0 || manualPitch != 0)) { stopMove(); delay(300) }
                media.stopRecording()
                media.stopStreaming()
                delay(1000)   // let the media scanner pick up the finished file and the stop frames go out
                ble.disconnect()
            }.onFailure { Log.w(TAG, "shutdown: ${it.message}") }
            done()
        }
    }

    fun setStreaming(on: Boolean): Boolean = if (on) media.startStreaming(_settings.value) else { media.stopStreaming(); true }
    fun setRecording(on: Boolean): Boolean = if (on) media.startRecording(_settings.value) else { media.stopRecording(); true }

    /** What the gimbal itself reports (0x46 ack track flag) — the switch shows the user's intent, this the truth. */
    @Volatile var gimbalTrackingActive: Boolean = false; private set
    private var trackingJob: Job? = null
    /** Set at SDKStart; the first SDK log line carrying a yaw tells whether the gimbal re-based its zero. */
    @Volatile private var rebasePending = false
    /** null = unknown / not checked yet, true = yaw re-based at the last start, false = old zero kept. */
    @Volatile var centreOk: Boolean? = null; private set
    @Volatile private var lastSdkStopAt = 0L
    /** See [ensurePrimed]. Reset on every link loss. */
    @Volatile private var primed = false
    @Volatile private var priming = false
    private var primingJob: Job? = null

    /**
     * VERIFIED 2026-10-17 (5 reconnect trials): the FIRST SDKStart after a BLE connection does NOT
     * re-base yaw 0 to the current direction — the gimbal keeps its old zero, tracks relative to it
     * and drives back there on stop. Every later start on the same connection re-bases correctly.
     * Re-sending the handshake does not help; a short SDK start/stop cycle right after connecting does
     * ("priming": the pitch nods to its initial angle and back, yaw stays). So prime once per
     * connection, and make every real start wait for it.
     */
    private suspend fun ensurePrimed() {
        if (primed || !gimbalReady()) return
        // One priming job per connection; everybody else (auto-start, manual start) just waits for it.
        val job = synchronized(this) { primingJob?.takeIf { it.isActive } ?: scope.launch { primeNow() }.also { primingJob = it } }
        job.join()
    }

    private suspend fun primeNow() {
        if (primed || !gimbalReady()) return
        if (gimbalTrackingActive) { primed = true; return }       // SDK already running on this connection
        priming = true
        gimbalNote = "preparing gimbal…"
        de.camleon.ble.ProtocolLog.event("priming cycle: SDK start/stop once after connect")
        ble.sendRaw(GimbalCommands.trackSwitch(true), "trackSwitch(on) [priming]"); delay(1200)
        ble.sendRaw(GimbalCommands.trackSwitch(false), "trackSwitch(off) [priming]")
        lastSdkStopAt = System.currentTimeMillis()
        primed = true
        delay(1500); waitUntilStill(3000)
        priming = false
        gimbalNote = null
    }

    /** Wait until the reported yaw/pitch stop changing (3 consecutive 200 ms samples) or [maxMs] passed. */
    private suspend fun waitUntilStill(maxMs: Long) {
        val t0 = System.currentTimeMillis()
        var ly = _gimbalYaw.value; var lp = _gimbalPitch.value; var stable = 0
        while (System.currentTimeMillis() - t0 < maxMs && stable < 3) {
            delay(200)
            val y = _gimbalYaw.value; val pt = _gimbalPitch.value
            val moved = (y != null && ly != null && kotlin.math.abs(y - ly) > 0.3) || (pt != null && lp != null && kotlin.math.abs(pt - lp) > 0.3)
            stable = if (moved) 0 else stable + 1
            ly = y; lp = pt
        }
    }

    /**
     * Tracking = the gimbal's onboard SDK. ON: mode → params (yaw limit) → SDKStart, **paced like the
     * required gaps (≈0.4 s / 0.35 s)** — sent back to back the gimbal ignores the start (verified live).
     * OFF: SDKStop.
     */
    fun setTracking(on: Boolean): Boolean {
        _tracking.value = on
        if (!gimbalReady()) { trackingNote = "gimbal not connected — tracking will apply once connected"; return false }
        trackingNote = null
        trackingJob?.cancel()
        val t = _settings.value.tracking
        if (!on) { lastSdkStopAt = System.currentTimeMillis(); centreOk = null; return ble.sendRaw(GimbalCommands.trackSwitch(false), "trackSwitch(off)") }
        val yawLim = t.yawRange
        val accel = t.acceleration
        val mode = modeId()
        trackingJob = scope.launch {
            ensurePrimed()
            if (!_tracking.value) return@launch
            // Pre-flight. The gimbal re-bases yaw 0 to its position at SDKStart, so it must be standing
            // still: no manual move running, and not still driving back to the OLD zero after a recent
            // SDKStop (an off→on toggle within ~3 s is the suspected "old zero kept" case).
            if (manualYaw != 0 || manualPitch != 0 || moveJob?.isActive == true) { stopMove(); delay(300) }
            val sinceStop = System.currentTimeMillis() - lastSdkStopAt
            if (sinceStop < 3000) { trackingNote = "waiting for the gimbal to settle…"; delay(3000 - sinceStop) }
            waitUntilStill(3000)
            trackingNote = null
            rebasePending = true; centreOk = null
            ble.sendRaw(GimbalCommands.chooseMode(mode), "chooseMode($mode)"); delay(400)
            ble.sendRaw(GimbalCommands.setParams(yawLim, accel), "setParams(yawRange=$yawLim, accel=$accel)"); delay(350)
            ble.sendRaw(GimbalCommands.trackSwitch(true), "trackSwitch(on)")
            delay(3000)
            if (_tracking.value && !gimbalTrackingActive) trackingNote = "gimbal did not confirm SDK start — try again"
        }
        return true
    }

    // ---- gimbal: manual control (setup + tracking override) ----------------------------------------

    private fun gimbalReady() = ble.state.value == ChameleonBleClient.ConnState.READY

    /**
     * Manual pan/tilt at -100..100 per axis (0 = stop). The move frame is repeated
     * while the joystick is held and sends 's' (stop) when released; we do the same at 10 Hz.
     * Works any time the gimbal is connected — during setup and as override while tracking.
     */
    @Volatile private var moveKeepAlive = 0L
    private val clientSeq = HashMap<String, Long>()

    /**
     * Ordering guard for HTTP clients: the browser sends move/keep-alive/stop over parallel connections,
     * so a stop can arrive before the move issued just before it. Requests carry a client id and a
     * monotonically increasing sequence number; anything older than the newest seen from that client is
     * stale and must be ignored. Requests without cid/seq are always accepted.
     */
    @Synchronized
    fun isStaleRequest(cid: String?, seq: String?): Boolean {
        if (cid.isNullOrBlank()) return false
        val s = seq?.toLongOrNull() ?: return false
        val last = clientSeq[cid]
        if (last != null && s < last) return true
        clientSeq[cid] = s
        return false
    }
    private const val KEEP_ALIVE_MS = 800L

    /**
     * Callers (web pad, phone pad) call this repeatedly (~every 300 ms) while a button is held — a
     * repeated call with the same direction is a keep-alive and does NOT restart the ramp. If the
     * keep-alives stop (page closed, connection lost, a stop request overtaken by its move) the motion
     * stops by itself after 800 ms (keep-alives arrive every ~300 ms).
     */
    @Synchronized
    fun move(yawPercent: Int, pitchPercent: Int): Boolean {
        val ny = yawPercent.coerceIn(-100, 100); val np = pitchPercent.coerceIn(-100, 100)
        if (!gimbalReady()) { manualYaw = 0; manualPitch = 0; gimbalNote = "gimbal not connected"; return false }
        if (homing && (ny != 0 || np != 0)) return false   // drive to 0° running: only Cancel stops it
        moveKeepAlive = System.currentTimeMillis()
        if (ny == manualYaw && np == manualPitch && moveJob?.isActive == true) return true   // keep-alive
        val wasYaw = manualYaw != 0; val wasPitch = manualPitch != 0
        manualYaw = ny; manualPitch = np
        _motion.value = ny to np
        moveJob?.cancel()
        if (ny == 0 && np == 0) {
            // Stop only the axes that were moving (twice, for safety). Frames are paced by the BLE
            // client; a stop that arrived back-to-back with another frame was dropped by the gimbal.
            val axes = buildList {
                if (wasPitch) add(GimbalCommands.AXIS_PITCH)
                if (wasYaw) add(GimbalCommands.AXIS_YAW)
                if (isEmpty()) { add(GimbalCommands.AXIS_YAW); add(GimbalCommands.AXIS_PITCH) }
            }
            repeat(2) { axes.forEach { ax -> ble.sendRaw(GimbalCommands.stop(ax), if (ax == GimbalCommands.AXIS_YAW) "stop yaw" else "stop pitch") } }
            return true
        }
        // UI semantics follow the VIDEO IMAGE: "right" = picture pans right, "up" = camera tilts up.
        // Verified by the user on hardware (2026-10-16): yaw is device-native ('f' = gimbal turns
        // right = picture pans right), pitch must be inverted ('b' = camera tilts up).
        val y = ny; val p = -np
        moveJob = scope.launch {
            // No ramp (user decision): the speed slider IS the speed. The frame is repeated every
            // 100 ms as a keep-alive for the gimbal (its own per-command timeout is ~1 s).
            var n = 0
            while (isActive && System.currentTimeMillis() - moveKeepAlive < KEEP_ALIVE_MS) {
                if (y != 0) ble.sendRaw(GimbalCommands.move(GimbalCommands.AXIS_YAW, y), if (n == 0) "move yaw $y%" else "move yaw (repeat)")
                if (p != 0) ble.sendRaw(GimbalCommands.move(GimbalCommands.AXIS_PITCH, p), if (n == 0) "move pitch $p%" else "move pitch (repeat)")
                n++; delay(100)
            }
            if (isActive) {   // keep-alive expired → stop on our own
                ble.sendRaw(GimbalCommands.stop(GimbalCommands.AXIS_YAW), "stop yaw (keep-alive expired)")
                ble.sendRaw(GimbalCommands.stop(GimbalCommands.AXIS_PITCH), "stop pitch (keep-alive expired)")
                manualYaw = 0; manualPitch = 0
                _motion.value = 0 to 0
            }
        }
        return true
    }
    fun stopMove() = move(0, 0)

    private var homeJob: Job? = null
    @Volatile var homing: Boolean = false; private set

    /**
     * Drive the gimbal to yaw 0° (optionally pitch 0°) using the status stream as feedback, so the
     * mount can then be aligned mechanically to the court's centre line. 0° = hardware zero
     * (direction at power-on; there is no command to redefine it).
     */
    fun goHome(includePitch: Boolean = false): Boolean = goHome(true, includePitch)

    /** Drive the selected axes to 0° (feedback loop on the reported angles). */
    fun goHome(doYaw: Boolean, doPitch: Boolean): Boolean {
        val includePitch = doPitch
        if (!doYaw && !doPitch) return false
        if (!gimbalReady()) { gimbalNote = "gimbal not connected"; return false }
        if (_gimbalYaw.value == null) { gimbalNote = "no gimbal angle yet"; return false }
        // Only one operation at a time: a running drive-to-0° is stopped only by cancelHome().
        if (homing) { ProtocolLog.event("go home ignored: another drive to 0° is running"); return false }
        moveJob?.cancel()
        homing = true; gimbalNote = null
        ProtocolLog.event("go home: ${listOfNotNull(if (doYaw) "yaw" else null, if (doPitch) "pitch" else null).joinToString("+")} → 0°")
        homeJob = scope.launch {
            val t0 = System.currentTimeMillis()
            var settled = 0
            while (isActive && System.currentTimeMillis() - t0 < 20_000) {
                val yaw = _gimbalYaw.value ?: 0.0
                val pitch = _gimbalPitch.value ?: 0.0
                val yawDone = !doYaw || kotlin.math.abs(yaw) <= 0.6
                val pitchDone = !includePitch || kotlin.math.abs(pitch) <= 0.6
                if (yawDone && pitchDone) { if (++settled >= 3) break } else settled = 0
                if (doYaw) {
                    if (!yawDone) {
                        val sp = (kotlin.math.abs(yaw) * 2.5).toInt().coerceIn(12, 60)
                        ble.sendRaw(GimbalCommands.move(GimbalCommands.AXIS_YAW, if (yaw < 0) sp else -sp), "home yaw")
                    } else ble.sendRaw(GimbalCommands.stop(GimbalCommands.AXIS_YAW), "home yaw stop")
                }
                if (includePitch) {
                    if (!pitchDone) {
                        val sp = (kotlin.math.abs(pitch) * 2.5).toInt().coerceIn(12, 60)
                        ble.sendRaw(GimbalCommands.move(GimbalCommands.AXIS_PITCH, if (pitch < 0) sp else -sp), "home pitch")
                    } else ble.sendRaw(GimbalCommands.stop(GimbalCommands.AXIS_PITCH), "home pitch stop")
                }
                delay(100)
            }
            repeat(2) { ble.sendRaw(GimbalCommands.stop(GimbalCommands.AXIS_YAW), "home done"); ble.sendRaw(GimbalCommands.stop(GimbalCommands.AXIS_PITCH), "home done"); delay(60) }
            homing = false
            ProtocolLog.event("go home finished at yaw=${"%.1f".format(_gimbalYaw.value ?: 0.0)}° pitch=${"%.1f".format(_gimbalPitch.value ?: 0.0)}°")
        }
        return true
    }
    fun cancelHome() { homeJob?.cancel(); homing = false; stopMove() }

    /** RX: 0x42 yaw/pitch (int16 LE, 1/100°), 0x46 ack [mode, capture, track], 0x67 battery, 0x66 external power. */
    private fun onGimbalFrame(f: ChameleonFrame.Parsed) {
        if (!f.crcOk) return
        val p = f.payload
        when (f.opcode) {
            0x42 -> if (p.size >= 4) {
                _gimbalYaw.value = ((p[0].toInt() and 0xFF) or (p[1].toInt() shl 8)).toShort().toInt() / 100.0
                _gimbalPitch.value = ((p[2].toInt() and 0xFF) or (p[3].toInt() shl 8)).toShort().toInt() / 100.0
            }
            0x46 -> if (p.size >= 3) {
                gimbalAck = Triple(p[0].toInt() and 0xFF, p[1].toInt() and 0xFF, p[2].toInt() and 0xFF)
                gimbalTrackingActive = p[2].toInt() == 1
                if (gimbalTrackingActive && !priming) { _tracking.value = true; trackingNote = null }
            }
            0x67 -> if (p.isNotEmpty()) gimbalBattery = p[0].toInt() and 0xFF
            // Sent every 10 s right after 0x67: 01 = USB power connected, 00 = on battery (verified).
            0x66 -> if (p.isNotEmpty()) gimbalPowered = p[0].toInt() == 1
            // While the tracking SDK runs the gimbal stops sending 0x42 (verified) but
            // prints "FrameN fps16 SZ0 Yaw-2.0 Pit11.4" per video frame → use that as telemetry.
            0x78 -> {
                val m = SDK_ANGLE.find(String(p, Charsets.US_ASCII)) ?: return
                _gimbalYaw.value = m.groupValues[1].toDoubleOrNull() ?: return
                _gimbalPitch.value = m.groupValues[2].toDoubleOrNull() ?: return
                angleSource = "sdk log"
                if (rebasePending && gimbalTrackingActive) {
                    // First SDK yaw after start: ~0 means the gimbal took the current direction as its
                    // new centre; a large value means it kept the old zero (and will return there on stop).
                    rebasePending = false
                    val y = _gimbalYaw.value ?: 0.0
                    centreOk = kotlin.math.abs(y) <= 5.0
                    if (centreOk == false) {
                        trackingNote = "centre NOT re-based at start (SDK yaw %.1f°) — switch tracking off and on again".format(y)
                        de.camleon.ble.ProtocolLog.event("REBASE FAILED: first SDK yaw $y")
                    } else de.camleon.ble.ProtocolLog.event("rebase ok: first SDK yaw $y")
                }
                return
            }
        }
        if (f.opcode == 0x42) angleSource = "status"
    }
    private val SDK_ANGLE = Regex("""Yaw(-?\d+(?:\.\d+)?) Pit(-?\d+(?:\.\d+)?)""")
    /** Where the last yaw/pitch came from: "status" (0x42 stream) or "sdk log" (0x78 while tracking). */
    @Volatile var angleSource: String = "status"; private set

    // ---- gimbal (BLE link + protocol tools) ----------------------------------------------------

    fun bleScan() = ble.startScan()
    fun bleConnect(address: String) = ble.connect(address)
    /** Manual disconnect (phone button, web button, /api/ble/disconnect): also pauses auto-connect. */
    fun bleDisconnect() = link.disconnectManually()
    fun setAutoConnect(on: Boolean) = link.setAutoConnect(on)
    fun forgetGimbal() { link.forget(); link.disconnectManually() }
    fun reconnectNow() { if (!gimbalReady()) { link.setAutoConnect(true); link.kick() } }
    fun bleSendRawHex(hex: String): Boolean = ble.sendRaw(ChameleonFrame.fromHex(hex), "raw")
    fun bleSendOp(opcode: Int, payloadHex: String): Boolean = ble.send(opcode, ChameleonFrame.fromHex(payloadHex), "op 0x%02X".format(opcode))
    fun protocolLogJson(limit: Int = 200) = ProtocolLog.toJson(limit.coerceIn(10, 2000))

    private fun linkJson() = JSONObject().apply {
        put("phase", link.phase.value); put("autoConnect", link.autoConnect.value); put("paused", link.paused); put("attempts", link.attempts)
        link.remembered.value?.let { put("remembered", JSONObject().put("address", it.address).put("name", it.name ?: JSONObject.NULL)) } ?: put("remembered", JSONObject.NULL)
    }

    // ---- browser preview (WebRTC) ----------------------------------------------------------

    fun webrtcOffer(sdp: String): WebRtcCamera.Answer { ensureCameraStarted(); return camera.acceptOffer(sdp) }
    fun webrtcClose(id: String) = camera.closePeer(id)

    // ---- status ------------------------------------------------------------------------------

    fun statusJson(): JSONObject {
        val st = media.stream.value
        val s = _settings.value
        return JSONObject().apply {
            put("streaming", JSONObject().apply {
                put("on", media.isStreaming())
                put("state", when (st) { MediaPipeline.StreamState.Off -> "off"; MediaPipeline.StreamState.Connecting -> "connecting"
                    MediaPipeline.StreamState.Live -> "live"; is MediaPipeline.StreamState.Error -> "error" })
                put("bitrateKbps", media.bitrateKbps.value)
                (st as? MediaPipeline.StreamState.Error)?.let { put("error", it.message) }
            })
            put("recording", JSONObject().apply { put("on", media.isRecording()); media.recordFile.value?.let { put("file", it) } })
            put("tracking", JSONObject().apply {
                put("on", _tracking.value); put("gimbalActive", gimbalTrackingActive); put("gimbalConnected", gimbalReady()); trackingNote?.let { put("note", it) }
                gimbalAck?.let { put("ack", JSONObject().put("mode", it.first).put("capture", it.second).put("track", it.third)) }
                centreOk?.let { put("centreOk", it) }
            })
            put("gimbal", JSONObject().apply {
                put("connected", gimbalReady())
                put("yaw", _gimbalYaw.value ?: JSONObject.NULL); put("pitch", _gimbalPitch.value ?: JSONObject.NULL)
                put("battery", gimbalBattery ?: JSONObject.NULL); put("powered", gimbalPowered ?: JSONObject.NULL)
                put("manualYaw", manualYaw); put("manualPitch", manualPitch); put("homing", homing); put("angleSource", angleSource)
                gimbalNote?.let { put("note", it) }
            })
            // Phone health (battery/power, thermal status, free storage) for remote monitoring.
            put("phone", de.camleon.common.PhoneStatus.toJson(appContext,
                s.quality.bitrateBps.toLong() + if (s.audioEnabled) AUDIO_BITRATE_BPS else 0L))
            put("version", BuildConfig.VERSION_NAME); put("debug", BuildConfig.DEBUG)
            put("locks", locks().toJson())
            put("modes", de.camleon.stream.ChameleonModes.toJson())
            put("preview", JSONObject().apply { put("cameraStarted", camera.started); put("cameraLost", camera.lost); put("viewers", camera.viewerCount())
                put("capture", "${camera.width}x${camera.height}@${camera.effectiveFps()}"); put("requestedFps", camera.fps); put("cameraMaxFps", camera.maxFpsFor(camera.width, camera.height));
                put("supportedQualities", org.json.JSONArray().apply { supportedQualities().forEach { q -> put(JSONObject().put("id", q.name).put("label", q.label)) } }); camera.lastError?.let { put("error", it) } })
            put("settings", s.toJson())
            put("link", linkJson())
            put("ble", JSONObject().apply {
                put("state", ble.state.value.name.lowercase())
                put("address", ble.connectedAddress ?: JSONObject.NULL); put("name", ble.connectedName ?: JSONObject.NULL); put("mtu", ble.mtu)
                put("found", JSONArray().also { arr -> ble.found.value.forEach { arr.put(JSONObject().put("address", it.address).put("name", it.name).put("rssi", it.rssi).put("gen2", it.hasGen2Service)) } })
            })
            media.lastError?.let { put("lastError", it) }
        }
    }

    private const val TAG = "CaptureSession"
    /** Audio bitrate of stream and recording (MediaPipeline.prepareAudio), for the remaining recording time. */
    private const val AUDIO_BITRATE_BPS = 128_000L
}
