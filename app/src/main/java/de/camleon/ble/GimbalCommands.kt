package de.camleon.ble

/**
 * Gimbal command frames (Chameleon), verified against the hardware. See docs/ble-protocol.md.
 * Frame = AA 55 | len lo hi | opcode | payload | CRC16 (poly 0x1021, init 0x0000, LE).
 *
 * TX (phone -> gimbal):
 *  0x41 move        [axis u8 0=yaw 1=pitch][dir 'f'=0x66 | 'b'=0x62 | 's'=0x73 stop][speed u16 LE 100..1000][250 u16 LE | 0 for stop]
 *                   repeat while moving (one frame moves for ~1 s); stop = 's' 0 0
 *  0x47 angles      [01|00]  enables the 0x42 yaw/pitch status stream
 *  0x70 mode        [modeId]  36 (0x24) = Basketball ("type36" in the gimbal log)
 *  0x72 params      [acceleration u16][0 u16][yawLim u16][0 u16]  e.g. 1000,0,85,0 → log "yawLim85"
 *  0x6F tracking    [01|00]  SDKStart / SDKStop on the gimbal (onboard tracking)
 *  0x5B field marks [count u8] + count × { type u8 (1 LT,2 LB,3 RB,4 RT,5 C), x u16 (%×100), y u16 (%×100), yaw i16 (°×100), pitch i16 (°×100) }
 *  handshake after connect: 0x76 01, 0x58 01, 0x62 01, 0x6B 08   (then 0x47 01 when the camera screen opens)
 *  simple requests (no payload): 0x77 api version, 0x57 firmware, 0x63 udid, 0x67 battery
 * RX (gimbal -> phone):
 *  0x42 status [yaw i16 ×0.01°][pitch i16 ×0.01°]   0x46 ack [modeId][capture][track]   0x67 battery [%]   0x66 external power [01 = USB, 00 = battery]
 *  0x78 log text (ASCII)   0x52 periodic value   0x6D capability table [id,01,val] ×16
 */
object GimbalCommands {
    const val AXIS_YAW = 0
    const val AXIS_PITCH = 1
    const val MODE_BASKETBALL = 36
    private const val SPEED_MIN = 100
    private const val SPEED_MAX = 1000
    private const val MOVE_CONST = 250

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
    private fun i16(v: Int) = u16(v and 0xFFFF)

    /** Pan/tilt at a signed percentage -100..100 (0 = stop). Positive yaw = 'f' (right), negative = 'b' (left). */
    fun move(axis: Int, percent: Int): ByteArray {
        val p = percent.coerceIn(-100, 100)
        if (p == 0) return stop(axis)
        val speed = (SPEED_MIN + (SPEED_MAX - SPEED_MIN) * (kotlin.math.abs(p) - 1) / 99).coerceIn(SPEED_MIN, SPEED_MAX)
        val dir = if (p > 0) 'f' else 'b'
        return ChameleonFrame.build(0x41, byteArrayOf(axis.toByte(), dir.code.toByte()) + u16(speed) + u16(MOVE_CONST))
    }
    fun stop(axis: Int): ByteArray = ChameleonFrame.build(0x41, byteArrayOf(axis.toByte(), 's'.code.toByte(), 0, 0, 0, 0))

    /** Live test 2026-10-16: 0x7A 01 starts the 0x42 yaw/pitch stream (0x47 alone does not). */
    fun angleReporting(on: Boolean) = ChameleonFrame.build(0x7A, byteArrayOf(if (on) 1 else 0))
    fun chooseMode(modeId: Int) = ChameleonFrame.build(0x70, byteArrayOf(modeId.toByte()))
    /**
     * Tracking parameters (0x72): (acceleration 200..1000, 0, camera rotation angle 60..150, 0).
     * Acceleration and camera rotation angle (gimbal log "yawLim"). Only applied at SDK start.
     */
    fun setParams(yawRangeDeg: Int, acceleration: Int = 1000) =
        ChameleonFrame.build(0x72, u16(acceleration.coerceIn(200, 1000)) + u16(0) + u16(yawRangeDeg.coerceIn(0, 180)) + u16(0))
    fun trackSwitch(on: Boolean) = ChameleonFrame.build(0x6F, byteArrayOf(if (on) 1 else 0))

    data class FieldMark(val type: Int, val xPct: Double, val yPct: Double, val yawDeg: Double, val pitchDeg: Double)
    fun fieldMarks(marks: List<FieldMark>): ByteArray {
        var payload = byteArrayOf(marks.size.toByte())
        marks.forEach { m ->
            payload += byteArrayOf(m.type.toByte()) + u16((m.xPct * 100).toInt()) + u16((m.yPct * 100).toInt()) +
                i16((m.yawDeg * 100).toInt()) + i16((m.pitchDeg * 100).toInt())
        }
        return ChameleonFrame.build(0x5B, payload)
    }

    /** Frames sent right after connecting (purpose partly unknown). */
    fun handshake(): List<Pair<String, ByteArray>> = listOf(
        "hs 0x76 01" to ChameleonFrame.build(0x76, byteArrayOf(1)),
        "hs 0x58 01" to ChameleonFrame.build(0x58, byteArrayOf(1)),
        "hs 0x62 01" to ChameleonFrame.build(0x62, byteArrayOf(1)),
        "hs 0x6B 08" to ChameleonFrame.build(0x6B, byteArrayOf(8)),
        "hs 0x47 01" to ChameleonFrame.build(0x47, byteArrayOf(1)),
    )

    fun requestApiVersion() = ChameleonFrame.build(0x77)
    fun requestFirmware() = ChameleonFrame.build(0x57)
    fun requestBattery() = ChameleonFrame.build(0x67)
}
