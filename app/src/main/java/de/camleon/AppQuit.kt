package de.camleon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Deliberate "quit": the only way besides Android's force stop to end CamLeon, because the foreground
 * service otherwise keeps camera, stream, gimbal link and web server alive when the UI is closed.
 * Unlike a force stop it finishes a running recording cleanly (a killed MP4 has no index and usually
 * does not play), stops tracking and the stream, drops the gimbal link, then stops the service and
 * ends the process (singletons, camera, WebRTC and the web server all go with it).
 * Triggered from the notification action and from the app's Quit button.
 */
object AppQuit {
    private const val TAG = "AppQuit"
    @Volatile private var activity: WeakReference<Activity>? = null
    @Volatile private var quitting = false

    /** The visible activity registers itself so a quit from the notification also removes its task. */
    fun attach(a: Activity) { activity = WeakReference(a) }

    fun quit(context: Context) {
        if (quitting) return
        quitting = true
        val app = context.applicationContext
        Log.i(TAG, "quit requested")
        activity?.get()?.finishAndRemoveTask()
        CaptureSession.shutdown {
            Handler(Looper.getMainLooper()).post {
                runCatching { app.stopService(Intent(app, CaptureService::class.java)) }
                Log.i(TAG, "bye")
                Process.killProcess(Process.myPid())
            }
        }
    }
}
