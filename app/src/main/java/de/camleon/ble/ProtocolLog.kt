package de.camleon.ble

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Ring buffer of BLE protocol events (TX/RX frames, connection events) that the browser control
 * page can read. This is the main tool for the on-device protocol work: every frame the gimbal
 * sends or receives is visible remotely, with hex, opcode name and CRC verdict.
 */
object ProtocolLog {
    data class Entry(val t: Long, val dir: String, val hex: String, val info: String)

    private const val CAPACITY = 2000
    private val entries = ArrayDeque<Entry>(CAPACITY)
    private var seq = 0L
    private var statusCount = 0L

    @Synchronized
    fun add(dir: String, bytes: ByteArray?, info: String) {
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(Entry(System.currentTimeMillis(), dir, bytes?.let { ChameleonFrame.hex(it) } ?: "", info))
        seq++
    }

    private var frameLogCount = 0L

    /**
     * Status frames (0x42 yaw/pitch, 0x52 periodic) arrive several times per second → log every 25th.
     * While the tracking SDK runs the gimbal prints ~5 log lines per video frame (16 fps) → keep every
     * SDK/Field/Start/Stop line but only every 50th per-frame line.
     */
    fun rx(frame: ChameleonFrame.Parsed) {
        if (frame.crcOk && (frame.opcode == 0x42 || frame.opcode == 0x52)) {
            statusCount++
            if (statusCount % 25 != 0L) return
        }
        if (frame.crcOk && frame.opcode == 0x78) {
            val text = String(frame.payload, Charsets.US_ASCII)
            val perFrame = text.startsWith("Frame") || text.contains("FIN:") || text.startsWith("AFilter") || text.startsWith("FFilter") ||
                text.startsWith("L0 ") || text.startsWith("BallSz")
            if (perFrame) { frameLogCount++; if (frameLogCount % 50 != 0L) return }
        }
        add(
            "RX", frame.raw,
            "${frame.opcodeName} len=${frame.payload.size} crc=${if (frame.crcOk) "ok(init=0x%04X)".format(frame.crcInitMatched) else "BAD"}" +
                if (frame.opcode == 0x42 || frame.opcode == 0x52) " (1 of 25 shown)" else ""
        )
    }
    /** Raw notification chunks are only logged when enabled (hundreds per second while the SDK logs). */
    @Volatile var logChunks: Boolean = false
    fun rxRaw(chunk: ByteArray) { if (logChunks) add("RXc", chunk, "notify chunk ${chunk.size}B") }
    fun tx(bytes: ByteArray, info: String) = add("TX", bytes, info)
    fun event(info: String) = add("EV", null, info)

    @Synchronized
    fun toJson(limit: Int = 200): JSONObject = JSONObject().apply {
        put("seq", seq)
        put("crcInit", "0x%04X".format(CrcCalib.init))
        put("crcConfirmed", CrcCalib.confirmed)
        put("entries", JSONArray().also { arr ->
            entries.toList().takeLast(limit).forEach { e ->
                arr.put(JSONObject().put("t", e.t).put("dir", e.dir).put("hex", e.hex).put("info", e.info))
            }
        })
    }

    @Synchronized fun clear() { entries.clear() }
}
