package de.camleon

import android.app.Application
import android.util.Log
import de.camleon.web.ControlServer

/**
 * Application entry point. Initializes the shared [CaptureSession] and starts the LAN control
 * server so the capture instance can be driven from a browser as soon as the app is launched
 * ("always-on remote": just start the app, do everything else from the browser). Camera, stream
 * and gimbal link keep running in the background through [CaptureService].
 */
class CaptureApp : Application() {
    private var server: ControlServer? = null

    override fun onCreate() {
        super.onCreate()
        CaptureSession.init(this)
        startServer()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var serverTries = 0

    /**
     * Port 8080 may still be held by another CamLeon build that is just quitting (debug next to release):
     * retry for a couple of minutes instead of running without a control page.
     */
    private fun startServer() {
        try {
            server = ControlServer(this).apply { start(SOCKET_READ_TIMEOUT, false) }
            Log.i(TAG, "control server started on port ${ControlServer.DEFAULT_PORT}")
        } catch (t: Throwable) {
            runCatching { server?.stop() }; server = null
            if (++serverTries < 40) { Log.w(TAG, "control server start failed (${t.message}) — retry in 3 s"); main.postDelayed(::startServer, 3_000) }
            else Log.e(TAG, "control server could not start", t)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        server?.stop()
    }

    private companion object {
        const val TAG = "CaptureApp"
        const val SOCKET_READ_TIMEOUT = 10_000
    }
}
