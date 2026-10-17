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
        try {
            server = ControlServer(this).apply { start(SOCKET_READ_TIMEOUT, false) }
            Log.i(TAG, "control server started on port ${ControlServer.DEFAULT_PORT}")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start control server", t)
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
