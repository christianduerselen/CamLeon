package de.camleon.stream

import android.content.Context
import android.os.Environment
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.library.base.recording.RecordController
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.util.sources.audio.MicrophoneSource
import com.pedro.library.util.sources.video.VideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Camera -> (preview | RTMP stream | local recording) via RootEncoder's [RtmpStream].
 *
 * Streaming and recording are two independent switches on the same encoded video: either can run
 * alone or both together. Nothing is forced — no recording is ever started implicitly — and there
 * is no time limit on either.
 *
 * RootEncoder rule (verified): `prepareVideo`/`prepareAudio` only while preview, stream and record
 * are all stopped, so encoders are prepared before the preview starts and a settings change while
 * nothing is running re-prepares around a preview restart.
 */
class MediaPipeline(
    private val context: Context,
    /** External camera feed (WebRTC fan-out). Null = RootEncoder opens the camera itself. */
    private val videoSource: VideoSource? = null,
) {
    sealed interface StreamState {
        data object Off : StreamState
        data object Connecting : StreamState
        data object Live : StreamState
        data class Error(val message: String) : StreamState
    }

    private val _stream = MutableStateFlow<StreamState>(StreamState.Off)
    val stream: StateFlow<StreamState> = _stream.asStateFlow()
    private val _bitrateKbps = MutableStateFlow(0L)
    val bitrateKbps: StateFlow<Long> = _bitrateKbps.asStateFlow()
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    private val _recordFile = MutableStateFlow<String?>(null)
    val recordFile: StateFlow<String?> = _recordFile.asStateFlow()
    private val _previewing = MutableStateFlow(false)
    val previewing: StateFlow<Boolean> = _previewing.asStateFlow()
    @Volatile var lastError: String? = null; private set

    private var rtmp: RtmpStream? = null
    private var previewView: SurfaceView? = null
    private var settings: CaptureSettings = CaptureSettings()
    private var preparedFor: CaptureSettings? = null

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) { _stream.value = StreamState.Connecting }
        override fun onConnectionSuccess() { _stream.value = StreamState.Live }
        override fun onConnectionFailed(reason: String) {
            // Network hiccups (screen lock, Wi-Fi roaming, server restart) must not end the stream:
            // RootEncoder re-dials after a short pause; the switch stays ON until the user turns it off.
            val retrying = runCatching { rtmp?.getStreamClient()?.reTry(5_000, reason) == true }.getOrDefault(false)
            if (retrying) { Log.w(TAG, "stream: $reason → reconnecting in 5 s"); lastError = "reconnecting: $reason"; _stream.value = StreamState.Connecting }
            else fail("stream: $reason")
        }
        override fun onDisconnect() { if (_stream.value !is StreamState.Error) _stream.value = StreamState.Off; _bitrateKbps.value = 0 }
        override fun onAuthError() { fail("stream: auth error") }
        override fun onAuthSuccess() {}
        override fun onNewBitrate(bitrate: Long) { _bitrateKbps.value = bitrate / 1000 }
    }

    private val recordListener = object : RecordController.Listener {
        override fun onStatusChange(status: RecordController.Status) {
            Log.i(TAG, "record status $status")
            when (status) {
                RecordController.Status.RECORDING, RecordController.Status.RESUMED -> _recording.value = true
                RecordController.Status.STOPPED -> { _recording.value = false; publishRecording() }
                else -> {}
            }
        }
        override fun onError(e: Exception) { lastError = "record: ${e.message}"; Log.e(TAG, "record error", e); _recording.value = false }
        override fun onNewBitrate(bitrate: Long) {}
    }

    private fun fail(msg: String) { lastError = msg; Log.e(TAG, msg); _stream.value = StreamState.Error(msg) }

    private fun engine(): RtmpStream = rtmp ?: run {
        val s = if (videoSource != null) {
            RtmpStream(context, connectChecker, videoSource, MicrophoneSource()).also {
                // Frames arrive already upright from the WebRTC capturer: disable RootEncoder's own
                // orientation handling AND its back-camera sensor assumption (default 90° → picture
                // rotated 90° CCW on a Galaxy S22 Ultra; verified fixed with cameraOrientation 0).
                it.getGlInterface().autoHandleOrientation = false
                it.getGlInterface().setCameraOrientation(0)
            }
        } else RtmpStream(context, connectChecker)
        rtmp = s; s
    }

    private fun anythingRunning(s: RtmpStream) = s.isStreaming || s.isRecording

    /**
     * RootEncoder re-applies a back-camera sensor orientation (90°) whenever its GL pipeline (re)starts,
     * which turns our already-upright frames by 90°. Must be re-asserted after every start of
     * preview / stream / record (setting it once at construction is overwritten — verified on device).
     */
    /**
     * The phone is mounted in landscape; the pipeline is landscape-only. RootEncoder re-derives
     * portrait/rotation from the display on every preview/stream start — coming back from the lock
     * screen (which is portrait) that produced a 9:16 preview rotated 90° — so pin everything down
     * after each start: forced landscape, not portrait, camera orientation 0, preview rotation 0.
     */
    private fun fixOrientation(s: RtmpStream) {
        runCatching {
            val g = s.getGlInterface()
            g.forceOrientation(com.pedro.library.view.OrientationForced.LANDSCAPE)
            g.setIsPortrait(false)
            g.setCameraOrientation(0)
            g.setPreviewRotation(0)
        }
    }

    // ---- preview ---------------------------------------------------------------------------

    fun bindPreview(surfaceView: SurfaceView) {
        previewView = surfaceView
        val s = engine()
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!anythingRunning(s) && preparedFor != settings) prepare(s, settings)
                if (!s.isOnPreview) s.startPreview(surfaceView)
                fixOrientation(s)
                _previewing.value = true
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (s.isOnPreview) s.stopPreview()
                _previewing.value = false
            }
        })
    }

    // ---- settings ----------------------------------------------------------------------------

    /** Apply settings. Video/audio changes take effect immediately only while nothing is running. */
    @Synchronized
    fun updateSettings(newSettings: CaptureSettings) {
        settings = newSettings
        val s = rtmp ?: return
        if (anythingRunning(s) || sameEncoderConfig(preparedFor, newSettings)) return
        reprepareAroundPreview(s, newSettings)
    }

    private fun sameEncoderConfig(a: CaptureSettings?, b: CaptureSettings) =
        a != null && a.quality == b.quality && a.audioEnabled == b.audioEnabled

    private fun reprepareAroundPreview(s: RtmpStream, st: CaptureSettings): Boolean {
        val wasPreviewing = s.isOnPreview
        if (wasPreviewing) s.stopPreview()
        val ok = prepare(s, st)
        if (wasPreviewing) previewView?.let { s.startPreview(it); fixOrientation(s) }
        return ok
    }

    private fun ensurePrepared(s: RtmpStream, st: CaptureSettings): Boolean {
        if (anythingRunning(s)) return true                      // can't re-prepare mid-run; keep current config
        return if (sameEncoderConfig(preparedFor, st)) true else reprepareAroundPreview(s, st)
    }

    /** Must be called with preview/stream/record stopped (RootEncoder requirement). */
    private fun prepare(s: RtmpStream, st: CaptureSettings): Boolean = try {
        val q = st.quality
        val videoOk = s.prepareVideo(q.width, q.height, q.bitrateBps, q.fps)
        val audioOk = if (st.audioEnabled) s.prepareAudio(44_100, true, 128_000) else true
        if (videoOk && audioOk) { preparedFor = st; true }
        else { preparedFor = null; fail("encoder prepare failed (video=$videoOk audio=$audioOk)"); false }
    } catch (t: Throwable) { preparedFor = null; fail("prepare failed: ${t.message}"); false }

    // ---- streaming ---------------------------------------------------------------------------

    @Synchronized
    fun startStreaming(st: CaptureSettings = settings): Boolean {
        settings = st
        if (!st.isUrlValid()) { fail("invalid RTMP URL"); return false }
        val s = engine()
        if (s.isStreaming) return true
        if (!ensurePrepared(s, st)) return false
        _stream.value = StreamState.Connecting
        runCatching { s.getStreamClient().setReTries(1_000_000) }   // effectively: retry until stopped
        s.startStream(st.rtmpUrl.trim())
        fixOrientation(s)
        return true
    }

    @Synchronized
    fun stopStreaming() {
        rtmp?.let { if (it.isStreaming) it.stopStream() }
        _bitrateKbps.value = 0
        _stream.value = StreamState.Off
    }

    fun isStreaming() = rtmp?.isStreaming == true

    // ---- recording ---------------------------------------------------------------------------

    /**
     * Records into the public **Movies/ChameleonCapture** folder via MediaStore, so files show up in
     * Gallery / Photos / Files immediately and over USB — no export step, no storage permission.
     * The muxer is handed the file through /proc/self/fd so no copy is needed afterwards.
     * Falls back to the app's private Movies dir if MediaStore is unavailable.
     */
    @Synchronized
    fun startRecording(st: CaptureSettings = settings): Boolean {
        settings = st
        val s = engine()
        if (s.isRecording) return true
        if (!ensurePrepared(s, st)) return false
        val name = "Chameleon_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        // Public Movies/ChameleonCapture via direct path (allowed for the app's own media files on
        // Android 11+ without any permission); after stop the file is handed to the media scanner so
        // Gallery / Files / USB show it. RootEncoder opens the path itself (RandomAccessFile), so a
        // /proc/self/fd indirection is not usable here. Falls back to the app's private Movies dir.
        var file = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), RECORD_FOLDER), name)
        var shown = "Movies/$RECORD_FOLDER/$name"
        val publicOk = runCatching {
            file.parentFile?.mkdirs()
            file.createNewFile().also { if (it) file.delete() }   // probe write access, muxer creates the real file
        }.getOrDefault(false)
        if (!publicOk) {
            Log.w(TAG, "public Movies not writable, falling back to app dir")
            file = File(File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), RECORD_FOLDER).apply { mkdirs() }, name)
            shown = file.absolutePath
        }
        return try {
            s.startRecord(file.absolutePath, recordListener)
            fixOrientation(s)
            recordPath = file.absolutePath
            _recordFile.value = shown
            _recording.value = true
            true
        } catch (t: Throwable) { lastError = "record start: ${t.message}"; Log.e(TAG, "startRecord", t); false }
    }

    @Synchronized
    fun stopRecording() {
        rtmp?.let { if (it.isRecording) it.stopRecord() }
        _recording.value = false
        publishRecording()
    }

    private var recordPath: String? = null

    /** Make the finished file visible to Gallery/Files (media scanner); no-op if nothing was recorded. */
    private fun publishRecording() {
        val p = recordPath ?: return
        recordPath = null
        runCatching {
            android.media.MediaScannerConnection.scanFile(context, arrayOf(p), arrayOf("video/mp4")) { path, uri -> Log.i(TAG, "scanned $path -> $uri") }
        }.onFailure { Log.w(TAG, "media scan: ${it.message}") }
    }

    fun isRecording() = rtmp?.isRecording == true

    fun recordingsDir(): File = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ChameleonCapture")

    // ---- orientation of the RootEncoder path (preview + stream + recording) ----------------------

    /**
     * Our frames arrive already upright from the WebRTC capturer, but RootEncoder applies its own
     * camera-orientation assumptions. These knobs act on the live GL pipeline so the right
     * combination can be found on a device without rebuilding; the result is then persisted.
     */
    fun setOrientation(cameraOrientation: Int?, rotation: Int?, portrait: Boolean?, forced: String?): String {
        val g = (rtmp ?: engine()).getGlInterface()
        val applied = mutableListOf<String>()
        cameraOrientation?.let { g.setCameraOrientation(it); applied += "cameraOrientation=$it" }
        rotation?.let { g.setRotation(it); g.setStreamRotation(it); g.setPreviewRotation(it); applied += "rotation=$it" }
        portrait?.let { g.setIsPortrait(it); applied += "portrait=$it" }
        forced?.let { f ->
            val v = when (f.lowercase()) {
                "landscape" -> com.pedro.library.view.OrientationForced.LANDSCAPE
                "portrait" -> com.pedro.library.view.OrientationForced.PORTRAIT
                else -> com.pedro.library.view.OrientationForced.NONE
            }
            g.forceOrientation(v); applied += "forced=$v"
        }
        return applied.joinToString(" ")
    }

    // ---- snapshot ----------------------------------------------------------------------------

    /** Current camera frame as JPEG (for marking the court without a live video path). Null if unavailable. */
    fun snapshotJpeg(quality: Int = 80): ByteArray? {
        val s = rtmp ?: return null
        if (!s.isOnPreview && !anythingRunning(s)) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: ByteArray? = null
        s.getGlInterface().takePhoto { bmp ->
            runCatching {
                val bos = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bos)
                out = bos.toByteArray()
            }
            latch.countDown()
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    fun release() {
        rtmp?.let {
            if (it.isRecording) it.stopRecord()
            if (it.isStreaming) it.stopStream()
            if (it.isOnPreview) it.stopPreview()
            it.release()
        }
        rtmp = null; preparedFor = null
        _stream.value = StreamState.Off; _recording.value = false; _previewing.value = false
    }

    private companion object { const val TAG = "MediaPipeline"; const val RECORD_FOLDER = "ChameleonCapture" }
}
