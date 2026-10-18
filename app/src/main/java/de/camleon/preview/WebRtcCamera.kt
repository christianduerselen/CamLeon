package de.camleon.preview

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.Surface
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.FpsCamera2Capturer
import org.webrtc.GlRectDrawer
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Single owner of the camera. Captures with libwebrtc's Camera2 capturer and fans the frames out:
 *  - to any number of browser viewers over WebRTC (low-latency preview for manual re-adjustment),
 *  - into an arbitrary output [Surface] via an [EglRenderer] — this is how RootEncoder (RTMP) is fed,
 *    see [WebRtcVideoSource]. One camera, two encoded outputs, no second camera session.
 *
 * Signaling is plain HTTP through the control server: the browser POSTs an SDP offer (recvonly),
 * we answer with all host ICE candidates gathered (non-trickle; LAN only, no STUN/TURN).
 */
class WebRtcCamera(private val context: Context) {

    @Volatile var width = 1280; private set
    @Volatile var height = 720; private set
    @Volatile var fps = 30; private set

    /**
     * The capture format follows the selected stream quality: the RTMP encoder cannot output more
     * pixels or frames than the camera delivers (1080p60 configured on a 720p30 capture was just an
     * upscaled 30 fps stream). Applied live when the camera is already running.
     */
    @Synchronized
    fun setCaptureFormat(w: Int, h: Int, f: Int) {
        if (w == width && h == height && f == fps) return
        width = w; height = h; fps = f
        if (started) runCatching { capturer?.changeCaptureFormat(w, h, f) }
            .onSuccess { Log.i(TAG, "capture format changed to ${w}x$h@$f") }
            .onFailure { Log.w(TAG, "changeCaptureFormat failed: ${it.message}") }
    }

    private val eglBase: EglBase = EglBase.create()
    private lateinit var factory: PeerConnectionFactory
    private var helper: SurfaceTextureHelper? = null
    private var capturer: CameraVideoCapturer? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private var outRenderer: EglRenderer? = null
    private var landscapeLock: LandscapeLock? = null
    @Volatile var started = false; private set
    @Volatile private var deviceName: String? = null
    /** True after the camera was taken away (error / disconnect / freeze) until frames flow again. */
    @Volatile var lost = false; private set
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val recoverRunnable = object : Runnable {
        override fun run() {
            if (!lost || !started) return
            Log.w(TAG, "camera lost → restarting capture ${width}x$height@$fps")
            runCatching { capturer?.stopCapture() }
            runCatching { capturer?.startCapture(width, height, fps) }.onFailure { Log.w(TAG, "restart failed: ${it.message}") }
            main.postDelayed(this, 5_000)        // keep trying until the first frame arrives
        }
    }
    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String) { markLost("camera error: $error") }
        override fun onCameraDisconnected() { markLost("camera disconnected") }
        override fun onCameraFreezed(error: String) { markLost("camera frozen: $error") }
        override fun onCameraOpening(name: String) {}
        override fun onFirstFrameAvailable() { if (lost) Log.i(TAG, "camera recovered"); lost = false; lastError = null; main.removeCallbacks(recoverRunnable) }
        override fun onCameraClosed() {}
    }
    private fun markLost(why: String) {
        lastError = why; Log.w(TAG, why)
        if (!lost) { lost = true; main.removeCallbacks(recoverRunnable); main.postDelayed(recoverRunnable, 3_000) }
    }
    /** Immediate recovery attempt (e.g. activity resumed) if the camera is currently lost. */
    fun recoverIfLost() { if (lost) { main.removeCallbacks(recoverRunnable); main.post(recoverRunnable) } }

    /**
     * Highest frame rate the camera offers for [w]x[h] through the public Camera2 API (0 = unknown):
     * the size's minimum frame duration, capped by the highest auto-exposure target range — both must
     * allow it, otherwise the capture runs slower than requested.
     * Example: a Galaxy S22 Ultra reports at most 30 fps for every normal mode; 60 fps simply does not
     * exist for third-party apps there (only 120/240 high-speed modes), so a "60 fps" quality streams at 30.
     */
    fun maxFpsFor(w: Int, h: Int): Int = runCatching {
        val name = deviceName ?: Camera2Enumerator(context).let { e -> e.deviceNames.firstOrNull { e.isBackFacing(it) } ?: e.deviceNames.firstOrNull() } ?: return 0
        val formats = Camera2Enumerator(context).getSupportedFormats(name) ?: return 0
        val bySize = (formats.filter { it.width == w && it.height == h }.ifEmpty { formats }).maxOfOrNull { it.framerate.max } ?: 0
        val aeMax = (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(name)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.maxOfOrNull { it.upper } ?: 0
        val aeMaxMilli = if (aeMax in 1 until 1000) aeMax * 1000 else aeMax          // some HALs report ×1000
        if (aeMaxMilli > 0) minOf(bySize, aeMaxMilli) else bySize
    }.getOrDefault(0) / 1000

    /** Frame rate the camera really runs at (upper bound of the AE range in use; device cap before the first start). */
    fun effectiveFps(): Int {
        if (started) FpsCamera2Capturer.activeMaxFps().takeIf { it > 0 }?.let { return it }
        val m = maxFpsFor(width, height); return if (m in 1 until fps) m else fps
    }
    @Volatile var lastError: String? = null; private set

    @Synchronized
    fun start(): Boolean {
        if (started) return true
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
            )
            // Surface native parse/negotiation errors in logcat (tag "libjingle" / "org.webrtc.Logging").
            org.webrtc.Logging.enableLogToDebugOutput(org.webrtc.Logging.Severity.LS_INFO)
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
            val enumerator = Camera2Enumerator(context)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
                ?: enumerator.deviceNames.firstOrNull() ?: error("no camera")
            deviceName = name
            // Own capturer: libwebrtc's range choice turns a 60 fps request into [10,30] on some phones.
            val cap: CameraVideoCapturer = FpsCamera2Capturer(context, name, cameraEvents)
            val h = SurfaceTextureHelper.create("CamCapture", eglBase.eglBaseContext)
            val src = factory.createVideoSource(cap.isScreencast)
            cap.initialize(h, context, LandscapeLock(src.capturerObserver).also { landscapeLock = it })
            cap.startCapture(width, height, fps)
            track = factory.createVideoTrack("cam0", src).also { it.setEnabled(true) }
            helper = h; capturer = cap; source = src
            started = true; lastError = null
            Log.i(TAG, "camera started: $name ${width}x$height@$fps")
            true
        } catch (t: Throwable) {
            lastError = t.message; Log.e(TAG, "start failed", t); false
        }
    }

    // ---- output surface (RootEncoder input) --------------------------------------------------

    /**
     * Render every captured frame into [surface] of the given size (used by [WebRtcVideoSource]).
     * Tapped at the capturer, not as a track sink: the track's frames pass libwebrtc's adapter, which
     * follows the browser viewers' wishes (30 fps preview cap) — a 1080p60 recording then got 30 fps
     * as soon as someone watched.
     */
    @Synchronized
    fun attachOutput(surface: Surface, w: Int, h: Int) {
        detachOutput()
        val r = EglRenderer("fanout")
        r.init(eglBase.eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        r.setLayoutAspectRatio(w.toFloat() / h.toFloat())
        r.createEglSurface(surface)
        landscapeLock?.tap = r
        outRenderer = r
        Log.i(TAG, "output attached ${w}x$h")
    }

    @Synchronized
    fun detachOutput() {
        outRenderer?.let { r ->
            landscapeLock?.tap = null
            val done = CountDownLatch(1)
            r.releaseEglSurface { done.countDown() }
            done.await(500, TimeUnit.MILLISECONDS)
            r.release()
        }
        outRenderer = null
    }

    // ---- snapshot ---------------------------------------------------------------------------

    /**
     * JPEG of the next camera frame, straight from the capture track. Works whenever the camera runs —
     * also with the phone locked and neither stream nor recording on, when the encoder pipeline (and
     * with it RootEncoder's own photo grab) is idle. Null if no frame arrives within 2 s.
     */
    fun snapshotJpeg(quality: Int = 80): ByteArray? {
        val t = track ?: return null
        val done = CountDownLatch(1)
        var out: ByteArray? = null
        val sink = object : org.webrtc.VideoSink {
            @Volatile var taken = false
            override fun onFrame(frame: org.webrtc.VideoFrame) {
                if (taken) return
                taken = true
                out = runCatching { frameToJpeg(frame, quality) }.onFailure { Log.w(TAG, "snapshot: ${it.message}") }.getOrNull()
                done.countDown()
            }
        }
        t.addSink(sink)
        try { done.await(2, TimeUnit.SECONDS) } finally { t.removeSink(sink) }
        return out
    }

    private fun frameToJpeg(frame: org.webrtc.VideoFrame, quality: Int): ByteArray {
        val i420 = frame.buffer.toI420() ?: error("no I420 buffer")
        try {
            val w = i420.width; val h = i420.height
            // I420 → NV21 (Y plane, then interleaved V/U) for YuvImage's JPEG encoder.
            val nv21 = ByteArray(w * h * 3 / 2)
            val y = i420.dataY; val u = i420.dataU; val v = i420.dataV
            for (row in 0 until h) { y.position(row * i420.strideY); y.get(nv21, row * w, w) }
            var o = w * h
            for (row in 0 until h / 2) for (col in 0 until w / 2) {
                nv21[o++] = v.get(row * i420.strideV + col); nv21[o++] = u.get(row * i420.strideU + col)
            }
            val bos = java.io.ByteArrayOutputStream()
            android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null).compressToJpeg(android.graphics.Rect(0, 0, w, h), quality, bos)
            if (frame.rotation % 360 == 0) return bos.toByteArray()
            // Frames tagged with a rotation (sensor orientation) are turned upright here.
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bos.toByteArray(), 0, bos.size())
            val m = android.graphics.Matrix().apply { postRotate(frame.rotation.toFloat()) }
            val rot = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            return java.io.ByteArrayOutputStream().also { rot.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        } finally { i420.release() }
    }

    // ---- browser viewers --------------------------------------------------------------------

    data class Answer(val id: String, val sdp: String)

    /** Accept a browser's SDP offer and return our answer with gathered host candidates. */
    fun acceptOffer(rawOffer: String): Answer {
        check(started) { "camera not started" }
        // HTTP servers like to trim the body; SDP parsers require every line (incl. the last) to be
        // terminated. Normalise to CRLF line endings with a trailing terminator.
        val offerSdp = rawOffer.trim().replace("\r\n", "\n").replace("\n", "\r\n") + "\r\n"
        val id = UUID.randomUUID().toString().substring(0, 8)
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val gathered = CountDownLatch(1)
        val pc = factory.createPeerConnection(cfg, object : PeerObserver() {
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown()
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                Log.i(TAG, "[$id] ice $s")
                if (s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.CLOSED) closePeer(id)
            }
        }) ?: error("createPeerConnection failed")
        peers[id] = pc

        Log.i(TAG, "[$id] offer ${offerSdp.length} chars, lines=${offerSdp.lines().size}, crlf=${offerSdp.contains("\r\n")}")
        pc.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, offerSdp))
            .getOrElse { closePeer(id); error("setRemoteDescription: ${it.message}") }
        // The browser's recvonly m-line created a video transceiver for us; attach the camera track to
        // THAT one and send on it (a transceiver added up-front would stay unassociated → nothing sent).
        val videoTransceiver = pc.transceivers.firstOrNull { it.mediaType == org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            ?: run { closePeer(id); error("offer has no video m-line") }
        videoTransceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
        videoTransceiver.sender.setTrack(track!!, false)
        // Prefer H.264 (hardware MediaCodec with direct texture input — no CPU YUV readback, and the
        // phone is already encoding H.264 for RTMP). Falls back to the browser's order (VP8) otherwise.
        runCatching {
            val caps = factory.getRtpSenderCapabilities(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val preferred = caps.filter { it.name.equals("H264", true) } + caps.filter { !it.name.equals("H264", true) }
            if (caps.any { it.name.equals("H264", true) }) videoTransceiver.setCodecPreferences(preferred)
            Log.i(TAG, "[$id] codecs available: ${caps.map { it.name }.distinct()}")
        }.onFailure { Log.w(TAG, "codec preference failed: ${it.message}") }
        val answer = pc.createAnswer(MediaConstraints())
            .getOrElse { closePeer(id); error("createAnswer: ${it.message}") }
        pc.setLocalDescription(answer)
            .getOrElse { closePeer(id); error("setLocalDescription: ${it.message}") }
        applyPreviewLimits(id, videoTransceiver.sender)
        gathered.await(2, TimeUnit.SECONDS)
        val sdp = pc.localDescription?.description ?: answer.description
        Log.i(TAG, "[$id] answer ready (${peers.size} viewer(s))")
        return Answer(id, sdp)
    }

    /**
     * Browser preview is for watching and re-aiming, not for detail: cap it at 540p / 30 fps / 1.5 Mbps
     * per viewer regardless of the capture format (each viewer has its own encoder; less heat).
     * On a weak link keep the frame rate and drop resolution, so manual control stays fluid.
     * Must run after setLocalDescription: before negotiation the sender of a transceiver created by the
     * browser's offer has no encodings yet, and the limits would silently not apply.
     */
    private fun applyPreviewLimits(id: String, sender: org.webrtc.RtpSender) = runCatching {
        val params = sender.parameters
        params.encodings.forEach { e ->
            e.maxFramerate = 30; e.maxBitrateBps = PREVIEW_MAX_BITRATE_BPS
            e.scaleResolutionDownBy = if (height > PREVIEW_MAX_HEIGHT) height / PREVIEW_MAX_HEIGHT.toDouble() else 1.0
        }
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        val ok = sender.setParameters(params)
        Log.i(TAG, "[$id] preview limits: encodings=${params.encodings.size} scale=${params.encodings.firstOrNull()?.scaleResolutionDownBy} ok=$ok")
    }.onFailure { Log.w(TAG, "[$id] sender parameters failed: ${it.message}") }

    fun closePeer(id: String) { peers.remove(id)?.let { runCatching { it.close() } } }
    fun viewerCount() = peers.size

    /** Debug: key send-side stats for every viewer (codec, frames encoded/sent, bytes, ICE pair). */
    fun statsAll(): org.json.JSONObject {
        val out = org.json.JSONObject()
        peers.forEach { (id, pc) ->
            val latch = CountDownLatch(1)
            val arr = org.json.JSONArray()
            pc.getStats { report ->
                report.statsMap.values.forEach { s ->
                    if (s.type in setOf("outbound-rtp", "codec", "candidate-pair", "media-source", "transport")) {
                        val m = s.members
                        if (s.type == "candidate-pair" && m["nominated"] != true) return@forEach
                        val o = org.json.JSONObject().put("type", s.type)
                        listOf("kind", "mimeType", "framesEncoded", "framesSent", "framesPerSecond", "bytesSent", "packetsSent",
                            "frameWidth", "frameHeight", "qualityLimitationReason", "encoderImplementation", "state",
                            "localCandidateId", "remoteCandidateId", "dtlsState", "iceState", "width", "height", "frames")
                            .forEach { k -> m[k]?.let { v -> o.put(k, v.toString()) } }
                        arr.put(o)
                    }
                }
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)
            out.put(id, arr)
        }
        return out
    }

    @Synchronized
    fun release() {
        peers.keys.toList().forEach { closePeer(it) }
        detachOutput()
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); source?.dispose(); helper?.dispose()
        track = null; capturer = null; source = null; helper = null; landscapeLock = null
        started = false
    }

    // ---- small blocking helpers over the async WebRTC API ------------------------------------

    private fun PeerConnection.setRemoteDescription(sd: SessionDescription): Result<Unit> =
        await { obs -> setRemoteDescription(obs, sd) }.map { }
    private fun PeerConnection.setLocalDescription(sd: SessionDescription): Result<Unit> =
        await { obs -> setLocalDescription(obs, sd) }.map { }
    private fun PeerConnection.createAnswer(c: MediaConstraints): Result<SessionDescription> =
        await { obs -> createAnswer(obs, c) }.mapCatching { it ?: error("no sdp") }

    private fun await(call: (SdpObserver) -> Unit): Result<SessionDescription?> {
        val latch = CountDownLatch(1)
        var result: Result<SessionDescription?> = Result.failure(IllegalStateException("timeout"))
        call(object : SdpObserver {
            override fun onCreateSuccess(sd: SessionDescription) { result = Result.success(sd); latch.countDown() }
            override fun onSetSuccess() { result = Result.success(null); latch.countDown() }
            override fun onCreateFailure(e: String) { result = Result.failure(IllegalStateException(e)); latch.countDown() }
            override fun onSetFailure(e: String) { result = Result.failure(IllegalStateException(e)); latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    private open class PeerObserver : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(c: IceCandidate) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
    }

    private companion object {
        const val TAG = "WebRtcCamera"
        const val PREVIEW_MAX_HEIGHT = 540
        const val PREVIEW_MAX_BITRATE_BPS = 1_500_000
    }
}

/**
 * The phone is mounted in landscape. libwebrtc tags every camera frame with a rotation derived from the
 * current DISPLAY rotation; while the screen is locked the display reports portrait, frames get tagged
 * 90°/270° and the browser preview turns 9:16. This observer keeps frames in landscape: portrait tags
 * are replaced by the last landscape rotation seen (0° by default = landscape with the camera on the
 * left, the usual mount). [tap] gets every frame first, before libwebrtc's adapter (RootEncoder output).
 */
private class LandscapeLock(private val downstream: org.webrtc.CapturerObserver) : org.webrtc.CapturerObserver {
    @Volatile private var lastLandscape = 0
    @Volatile var tap: org.webrtc.VideoSink? = null
    override fun onCapturerStarted(success: Boolean) = downstream.onCapturerStarted(success)
    override fun onCapturerStopped() = downstream.onCapturerStopped()
    override fun onFrameCaptured(frame: org.webrtc.VideoFrame) {
        val r = frame.rotation
        if (r == 0 || r == 180) lastLandscape = r
        val out = if (r == 0 || r == 180) frame
            else org.webrtc.VideoFrame(frame.buffer, lastLandscape, frame.timestampNs)   // buffer stays owned by the original frame (no extra retain/release)
        tap?.onFrame(out)   // EglRenderer retains the buffer itself while it renders
        downstream.onFrameCaptured(out)
    }
}
