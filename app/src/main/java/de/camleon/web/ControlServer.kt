package de.camleon.web

import android.content.Context
import android.util.Log
import de.camleon.BuildConfig
import de.camleon.CaptureSession
import de.camleon.ble.CrcCalib
import de.camleon.ble.ProtocolLog
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Embedded LAN control server: serves the browser control page and a JSON API over the shared
 * [CaptureSession]. Everything the on-device UI can do is available here.
 *
 *   GET  /                          control page
 *   GET  /api/status                full state (streaming/recording/tracking/gimbal/preview/settings/ble)
 *   POST /api/streaming?on=true|false
 *   POST /api/recording?on=true|false
 *   POST /api/tracking?on=true|false
 *   POST /api/settings?url=&streamAutoStart=&recordAutoStart=&quality=&audio=
 *                      &sport=&variant=&youth=&yawRange=&acceleration=   (any subset; yawRange/acceleration ignored while tracking runs)
 *   Gimbal manual control / pan range (also tracking override):
 *   POST /api/gimbal/move?yaw=-100..100&pitch=-100..100   (0,0 = stop)   POST /api/gimbal/stop
 *   POST /api/gimbal/home?axis=yaw|pitch|both (drive the axis to 0°)   POST /api/gimbal/home/cancel
 *   Gimbal link:  POST /api/ble/scan | connect?address= | disconnect | send?hex= | op?op=&payload= | crcinit?value=
 *                 GET  /api/ble/log  | POST /api/ble/log/clear
 *   Preview: POST /api/webrtc/offer (body = SDP) -> {id,sdp} | POST /api/webrtc/stop?id= | GET /api/webrtc/stats
 *            GET  /api/snapshot.jpg
 */
class ControlServer(private val context: Context, port: Int = DEFAULT_PORT) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val post = session.method == Method.POST
        val p = session.parameters
        fun param(k: String) = p[k]?.firstOrNull()
        fun on() = param("on")?.toBooleanStrictOrNull()
        return try {
            when {
                uri == "/" || uri == "/index.html" -> servePage()
                uri == "/favicon-64.png" || uri == "/apple-touch-icon.png" -> {
                    val png = context.assets.open("web" + uri).readBytes()
                    newFixedLengthResponse(Response.Status.OK, "image/png", java.io.ByteArrayInputStream(png), png.size.toLong())
                        .also { it.addHeader("Cache-Control", "max-age=86400") }
                }
                uri == "/favicon.svg" || uri == "/favicon.ico" -> {
                    val svg = context.assets.open("web/favicon.svg").readBytes()
                    newFixedLengthResponse(Response.Status.OK, "image/svg+xml", java.io.ByteArrayInputStream(svg), svg.size.toLong())
                        .also { it.addHeader("Cache-Control", "max-age=86400") }
                }
                uri == "/api/status" -> json(CaptureSession.statusJson())

                uri == "/api/streaming" && post -> { val v = on() ?: return bad("on=true|false"); result(CaptureSession.setStreaming(v)) }
                uri == "/api/recording" && post -> { val v = on() ?: return bad("on=true|false"); result(CaptureSession.setRecording(v)) }
                uri == "/api/tracking" && post -> { val v = on() ?: return bad("on=true|false"); result(CaptureSession.setTracking(v)) }
                uri == "/api/settings" && post -> { CaptureSession.updateSettings(p.mapValues { it.value.firstOrNull() }); json(CaptureSession.statusJson()) }

                uri == "/api/gimbal/move" && post -> {
                    val stale = CaptureSession.isStaleRequest(param("cid"), param("seq"))
                    ProtocolLog.event("http move yaw=${param("yaw")} pitch=${param("pitch")} cid=${param("cid")} seq=${param("seq")} from=${session.remoteIpAddress}" + (if (stale) " STALE" else ""))
                    if (stale) json("""{"ok":true,"ignored":"stale"}""")
                    else result(CaptureSession.move(param("yaw")?.toIntOrNull() ?: 0, param("pitch")?.toIntOrNull() ?: 0))
                }
                uri == "/api/gimbal/stop" && post -> {
                    val stale = CaptureSession.isStaleRequest(param("cid"), param("seq"))
                    ProtocolLog.event("http stop cid=${param("cid")} seq=${param("seq")} from=${session.remoteIpAddress}" + (if (stale) " STALE" else ""))
                    if (stale) json("""{"ok":true,"ignored":"stale"}""")
                    else result(CaptureSession.stopMove())
                }
                uri == "/api/gimbal/home" && post -> result(when (param("axis")) {
                    "yaw" -> CaptureSession.goHome(true, false)
                    "pitch" -> CaptureSession.goHome(false, true)
                    "both" -> CaptureSession.goHome(true, true)
                    else -> CaptureSession.goHome(true, param("pitch")?.toBooleanStrictOrNull() ?: false)
                })
                uri == "/api/gimbal/home/cancel" && post -> { CaptureSession.cancelHome(); ok() }

                uri == "/api/gimbal/autoconnect" && post -> { val v = on() ?: return bad("on=true|false"); CaptureSession.setAutoConnect(v); json(CaptureSession.statusJson()) }
                uri == "/api/gimbal/forget" && post -> { CaptureSession.forgetGimbal(); json(CaptureSession.statusJson()) }
                uri == "/api/gimbal/reconnect" && post -> { CaptureSession.reconnectNow(); json(CaptureSession.statusJson()) }

                uri == "/api/ble/scan" && post -> { CaptureSession.bleScan(); ok() }
                uri == "/api/ble/connect" && post -> { CaptureSession.bleConnect(param("address") ?: return bad("address required")); ok() }
                uri == "/api/ble/disconnect" && post -> { CaptureSession.bleDisconnect(); ok() }
                // ---- developer tools: only in debug builds (raw BLE frames, CRC override, protocol log, WebRTC stats, orientation knobs)
                !BuildConfig.DEBUG && uri in DEBUG_ONLY -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not available in release builds")
                uri == "/api/ble/send" && post -> result(CaptureSession.bleSendRawHex(param("hex") ?: return bad("hex required")))
                uri == "/api/ble/op" && post -> {
                    val op = param("op")?.let { parseInt(it) } ?: return bad("op required")
                    result(CaptureSession.bleSendOp(op, param("payload") ?: ""))
                }
                uri == "/api/ble/crcinit" && post -> {
                    val v = param("value")?.let { parseInt(it) } ?: return bad("value required")
                    CrcCalib.override(v); ProtocolLog.event("crc init overridden to 0x%04X".format(v)); ok()
                }
                uri == "/api/ble/log" -> json(CaptureSession.protocolLogJson(param("limit")?.toIntOrNull() ?: 200))
                uri == "/api/ble/log/clear" && post -> { ProtocolLog.clear(); ok() }

                uri == "/api/webrtc/offer" && post -> {
                    val offer = readBody(session)
                    if (offer.isBlank()) return bad("sdp offer body required")
                    val a = CaptureSession.webrtcOffer(offer)
                    json(JSONObject().put("id", a.id).put("sdp", a.sdp))
                }
                uri == "/api/webrtc/stop" && post -> { param("id")?.let { CaptureSession.webrtcClose(it) }; ok() }
                uri == "/api/webrtc/stats" -> json(CaptureSession.camera.statsAll())
                uri == "/api/video/orient" && post -> json(JSONObject().put("applied", CaptureSession.media.setOrientation(
                    param("camera")?.toIntOrNull(), param("rotation")?.toIntOrNull(), param("portrait")?.toBooleanStrictOrNull(), param("forced"))))
                uri == "/api/snapshot.jpg" -> {
                    val jpg = CaptureSession.media.snapshotJpeg() ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "camera not running")
                    newFixedLengthResponse(Response.Status.OK, "image/jpeg", java.io.ByteArrayInputStream(jpg), jpg.size.toLong()).also { it.addHeader("Cache-Control", "no-store") }
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "serve error", t)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error: ${t.message}")
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: files.values.firstOrNull()?.let { path -> runCatching { java.io.File(path).readText() }.getOrNull() } ?: ""
    }

    private fun parseInt(s: String): Int? = s.trim().let { if (it.startsWith("0x", true)) it.substring(2).toIntOrNull(16) else it.toIntOrNull() }

    private fun servePage(): Response {
        val html = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun ok() = json("""{"ok":true}""")
    private fun result(ok: Boolean) = json(JSONObject().put("ok", ok).put("status", CaptureSession.statusJson()))
    private fun bad(msg: String) = newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, msg)
    private fun json(o: JSONObject) = json(o.toString())
    private fun json(body: String): Response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)

    private val DEBUG_ONLY = setOf(
        "/api/ble/send", "/api/ble/op", "/api/ble/crcinit", "/api/ble/log", "/api/ble/log/clear",
        "/api/webrtc/stats", "/api/video/orient",
    )

    companion object { const val DEFAULT_PORT = 8080; private const val TAG = "ControlServer" }
}
