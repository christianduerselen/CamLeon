package de.camleon.ble

import java.util.UUID

/**
 * Wire format of the Chameleon BLE protocol (see docs/ble-protocol.md).
 *
 *   byte 0..1  : header 0xAA 0x55
 *   byte 2..3  : payload length N  (byte2 = low, byte3 = high)
 *   byte 4     : opcode
 *   byte 5..   : payload (N-1 bytes; the opcode counts towards N)   -- total frame = N + 6
 *   last 2     : CRC-16 (poly 0x1021, table driven, MSB-first) over all preceding bytes,
 *                appended little-endian. The start value is calibrated at runtime (see [CrcCalib]).
 *
 * GATT: service 0000AE30-…, write characteristic 0000AE01-…, notify characteristic 0000AE02-….
 */
object ChameleonFrame {
    val SERVICE: UUID = UUID.fromString("0000AE30-0000-1000-8000-00805F9B34FB")
    val WRITE_CHAR: UUID = UUID.fromString("0000AE01-0000-1000-8000-00805F9B34FB")
    val NOTIFY_CHAR: UUID = UUID.fromString("0000AE02-0000-1000-8000-00805F9B34FB")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val HDR0 = 0xAA.toByte()
    const val HDR1 = 0x55.toByte()
    private const val MAX_PAYLOAD = 500

    /** Opcode names for the protocol log. */
    val OPCODE_NAMES: Map<Int, String> = mapOf(
        0x41 to "move (axis,dir,speed)", 0x42 to "status yaw/pitch", 0x43 to "status 'C'", 0x46 to "ack [mode,capture,track]",
        0x47 to "handshake 0x47 (pre-angle)", 0x51 to "ota transfer", 0x52 to "periodic value 'R'", 0x55 to "ota install",
        0x57 to "firmware info", 0x58 to "handshake 0x58", 0x5B to "field marks", 0x5D to "voltage/power",
        0x62 to "handshake 0x62", 0x63 to "udid", 0x66 to "external power (01 = USB)", 0x67 to "battery %", 0x6A to "limit switch",
        0x6B to "handshake 0x6B", 0x6D to "capability entry", 0x6F to "tracking SDK start/stop", 0x70 to "choose mode",
        0x71 to "angle 'q'", 0x72 to "tracking params (p1,p2,yawLim,p4)", 0x73 to "status 's'", 0x76 to "handshake 0x76",
        0x77 to "api version", 0x78 to "gimbal log text", 0x79 to "error code", 0x7A to "angle reporting on/off",
    )

    private val TABLE: IntArray = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 8
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
            t[i] = c
        }
    }

    fun crc16(data: ByteArray, from: Int, to: Int, init: Int): Int {
        var crc = init and 0xFFFF
        for (i in from until to) {
            val idx = ((crc ushr 8) xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = (TABLE[idx] xor (crc shl 8)) and 0xFFFF
        }
        return crc
    }

    /** Build a complete frame for [opcode] + [payload] using the current calibrated CRC start value. */
    fun build(opcode: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val n = payload.size + 1
        val out = ByteArray(n + 6)
        out[0] = HDR0; out[1] = HDR1
        out[2] = (n and 0xFF).toByte(); out[3] = ((n ushr 8) and 0xFF).toByte()
        out[4] = opcode.toByte()
        payload.copyInto(out, 5)
        val crc = crc16(out, 0, out.size - 2, CrcCalib.init)
        out[out.size - 2] = (crc and 0xFF).toByte()
        out[out.size - 1] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    data class Parsed(val raw: ByteArray, val opcode: Int, val payload: ByteArray, val crcOk: Boolean, val crcInitMatched: Int?) {
        val opcodeName: String get() = OPCODE_NAMES[opcode] ?: "opcode 0x%02X".format(opcode)
    }

    /** Validate CRC trying the calibrated start value first, then the other candidates. */
    fun parse(raw: ByteArray): Parsed {
        val opcode = raw[4].toInt() and 0xFF
        val payload = raw.copyOfRange(5, raw.size - 2)
        val got = (raw[raw.size - 2].toInt() and 0xFF) or ((raw[raw.size - 1].toInt() and 0xFF) shl 8)
        val candidates = listOf(CrcCalib.init) + CrcCalib.CANDIDATES.filter { it != CrcCalib.init }
        val matched = candidates.firstOrNull { crc16(raw, 0, raw.size - 2, it) == got }
        if (matched != null) CrcCalib.onMatch(matched)
        return Parsed(raw, opcode, payload, matched != null, matched)
    }

    /**
     * Reassembles notification chunks into whole frames:
     * accumulate, sync on AA 55, read the length, emit when complete, keep the remainder.
     */
    class Reassembler(private val onFrame: (Parsed) -> Unit) {
        private var buf = ByteArray(0)

        @Synchronized
        fun feed(chunk: ByteArray) {
            buf += chunk
            if (buf.size > 1000) { buf = ByteArray(0); return }
            drain()
        }

        private fun drain() {
            while (true) {
                // sync to header
                var i = 0
                while (i + 1 < buf.size && !(buf[i] == HDR0 && buf[i + 1] == HDR1)) i++
                if (i > 0) buf = buf.copyOfRange(i, buf.size)
                if (buf.size < 4) return
                val n = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
                if (n > MAX_PAYLOAD) { buf = ByteArray(0); return }
                val total = n + 6
                if (buf.size < total) return
                val frame = buf.copyOfRange(0, total)
                buf = buf.copyOfRange(total, buf.size)
                onFrame(parse(frame))
            }
        }

        @Synchronized fun reset() { buf = ByteArray(0) }
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it) }

    fun fromHex(s: String): ByteArray =
        s.trim().replace(Regex("[^0-9A-Fa-f]"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * CRC start value: 0x0000 on the Chameleon. As a safeguard the usual candidates are tried and the
 * first one that validates a received frame is locked in; TX frames then use the same value.
 */
object CrcCalib {
    val CANDIDATES = listOf(0x0000, 0xFFFF, 0x1D0F)
    @Volatile var init: Int = 0x0000
        private set
    @Volatile var confirmed: Boolean = false
        private set

    fun onMatch(value: Int) {
        if (!confirmed || value != init) { init = value; confirmed = true }
    }

    fun override(value: Int) { init = value and 0xFFFF; confirmed = false }
}
