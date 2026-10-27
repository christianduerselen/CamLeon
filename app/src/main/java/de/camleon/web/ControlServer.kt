package de.camleon.web

import android.content.Context
import android.util.Log
import de.camleon.BuildConfig
import de.camleon.CaptureSession
import de.camleon.ble.CrcCalib
import de.camleon.ble.ProtocolLog
import de.camleon.stream.Recordings
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
 *   POST /api/settings?url=&streamAutoStart=&recordAutoStart=&quality=&audio=&previewQuality=
 *                      &sport=&variant=&youth=&yawRange=&acceleration=&speed=   (any subset; yawRange/acceleration ignored while tracking runs; speed = pad speed 10..100)
 *   Gimbal manual control / pan range (also tracking override):
 *   POST /api/gimbal/move?yaw=-100..100&pitch=-100..100   (0,0 = stop)   POST /api/gimbal/stop
 *   POST /api/gimbal/home?axis=yaw|pitch|both (drive the axis to 0°)   POST /api/gimbal/home/cancel
 *   Gimbal link:  POST /api/ble/scan | connect?address= | disconnect | send?hex= | op?op=&payload= | crcinit?value=
 *                 GET  /api/ble/log  | POST /api/ble/log/clear
 *   Preview: POST /api/webrtc/offer (body = SDP) -> {id,sdp} | POST /api/webrtc/stop?id= | GET /api/webrtc/stats
 *            GET  /api/snapshot.jpg
 *   Remote view (page: GET /remote): full-screen preview + overlay gimbal control, uses the APIs above.
 *   Recordings (page: GET /recordings):
 *   GET  /api/recordings                       list {recordings:[{name,size,modified,durationMs,recording,playable,deletable}],totalBytes}
 *   GET  /api/recordings/file?name=&download=1 the MP4 (Range requests supported; download=1 → attachment)
 *   POST /api/recordings/delete?name=
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
                uri == "/" || uri == "/index.html" -> servePage("web/index.html")
                uri == "/recordings" || uri == "/recordings.html" -> servePage("web/recordings.html")
                uri == "/remote" || uri == "/remote.html" -> servePage("web/remote.html")
                uri == "/control.js" || uri == "/i18n.js" -> {
                    val js = context.assets.open("web" + uri).readBytes()
                    newFixedLengthResponse(Response.Status.OK, "application/javascript; charset=utf-8", java.io.ByteArrayInputStream(js), js.size.toLong())
                        .also { it.addHeader("Cache-Control", "no-cache") }     // changes with every version
                }
                uri == "/manifest.webmanifest" -> {
                    val m = context.assets.open("web/manifest.webmanifest").readBytes()
                    newFixedLengthResponse(Response.Status.OK, "application/manifest+json", java.io.ByteArrayInputStream(m), m.size.toLong())
                        .also { it.addHeader("Cache-Control", "max-age=86400") }
                }
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
                    val jpg = CaptureSession.camera.snapshotJpeg() ?: CaptureSession.media.snapshotJpeg() ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "camera not running")
                    newFixedLengthResponse(Response.Status.OK, "image/jpeg", java.io.ByteArrayInputStream(jpg), jpg.size.toLong()).also { it.addHeader("Cache-Control", "no-store") }
                }

                uri == "/api/recordings" -> json(CaptureSession.recordings.toJson())
                uri == "/api/recordings/file" -> {
                    val f = CaptureSession.recordings.find(param("name") ?: "") ?: return notFound()
                    if (CaptureSession.recordings.isRecording(f)) return newFixedLengthResponse(Response.Status.CONFLICT, MIME_PLAINTEXT, "still recording")
                    serveFile(session, f, download = param("download") == "1")
                }
                uri == "/api/recordings/delete" && post -> when (CaptureSession.recordings.delete(param("name") ?: "")) {
                    Recordings.DeleteResult.Ok -> ok()
                    Recordings.DeleteResult.NotFound -> notFound()
                    Recordings.DeleteResult.Recording -> newFixedLengthResponse(Response.Status.CONFLICT, MIME_PLAINTEXT, "still recording")
                    Recordings.DeleteResult.NotAllowed -> newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "not deletable from here")
                }

                else -> notFound()
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

    private fun servePage(asset: String): Response {
        val html = context.assets.open(asset).bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    /**
     * Stream a recording with HTTP Range support: browsers seek in the <video> element and resume
     * downloads with range requests (Safari insists on them for video at all).
     */
    private fun serveFile(session: IHTTPSession, f: java.io.File, download: Boolean): Response {
        val len = f.length()
        var start = 0L
        var end = len - 1
        val range = session.headers["range"]
        val partial = range != null && range.startsWith("bytes=")
        if (partial) {
            val spec = range!!.removePrefix("bytes=").substringBefore(',').trim()
            val a = spec.substringBefore('-'); val b = spec.substringAfter('-', "")
            if (a.isEmpty()) start = (len - (b.toLongOrNull() ?: 0L)).coerceAtLeast(0L)
            else { start = a.toLongOrNull() ?: 0L; b.toLongOrNull()?.let { end = minOf(it, len - 1) } }
            if (start > end || start >= len) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    .also { it.addHeader("Content-Range", "bytes */$len") }
            }
        }
        val input = java.io.FileInputStream(f).also { it.channel.position(start) }
        val r = newFixedLengthResponse(if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK, "video/mp4", input, end - start + 1)
        r.addHeader("Accept-Ranges", "bytes")
        if (partial) r.addHeader("Content-Range", "bytes $start-$end/$len")
        r.addHeader("Content-Disposition", (if (download) "attachment" else "inline") + "; filename=\"${f.name}\"")
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun ok() = json("""{"ok":true}""")
    private fun result(ok: Boolean) = json(JSONObject().put("ok", ok).put("status", CaptureSession.statusJson()))
    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
    private fun bad(msg: String) = newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, msg)
    private fun json(o: JSONObject) = json(o.toString())
    private fun json(body: String): Response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)

    private val DEBUG_ONLY = setOf(
        "/api/ble/send", "/api/ble/op", "/api/ble/crcinit", "/api/ble/log", "/api/ble/log/clear",
        "/api/webrtc/stats", "/api/video/orient",
    )

    companion object { const val DEFAULT_PORT = 8080; private const val TAG = "ControlServer" }
}
