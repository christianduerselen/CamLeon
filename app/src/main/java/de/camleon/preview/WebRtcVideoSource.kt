package de.camleon.preview

import android.graphics.SurfaceTexture
import android.view.Surface
import com.pedro.library.util.sources.video.VideoSource

/**
 * RootEncoder [VideoSource] that is fed by [WebRtcCamera] instead of opening its own camera.
 *
 * RootEncoder hands us its input SurfaceTexture in [start]; we wrap it in a Surface and ask the
 * camera to render every frame into it. Everything downstream in RootEncoder (local preview,
 * H.264 encoder, RTMP) is unchanged — it just never owns the camera itself.
 */
class WebRtcVideoSource(private val camera: WebRtcCamera) : VideoSource() {

    private var surface: Surface? = null
    @Volatile private var running = false

    /** RootEncoder tells us the encoder format here (prepareVideo) → make the camera capture exactly that. */
    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        camera.setCaptureFormat(width, height, fps)
        return true
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        surfaceTexture.setDefaultBufferSize(width, height)
        val s = Surface(surfaceTexture)
        surface = s
        camera.start()                    // no-op if already running (needs CAMERA permission)
        camera.attachOutput(s, width, height)
        running = true
    }

    override fun stop() {
        running = false
        camera.detachOutput()
        surface?.release(); surface = null
    }

    override fun release() { stop() }

    override fun isRunning(): Boolean = running
}
