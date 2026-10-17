package de.camleon.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChameleonFrameTest {

    private val check = "123456789".toByteArray()

    @Test
    fun crcTableMatchesKnownVectors() {
        // CRC-16/CCITT-FALSE (init 0xFFFF) and CRC-16/XMODEM (init 0x0000) over "123456789"
        assertEquals(0x29B1, ChameleonFrame.crc16(check, 0, check.size, 0xFFFF))
        assertEquals(0x31C3, ChameleonFrame.crc16(check, 0, check.size, 0x0000))
    }

    @Test
    fun buildProducesExpectedLayout() {
        CrcCalib.override(0x0000)
        val f = ChameleonFrame.build(0x77, byteArrayOf(0x01))
        assertEquals(8, f.size)                       // 2 hdr + 2 len + opcode + 1 payload + 2 crc
        assertEquals(0xAA.toByte(), f[0]); assertEquals(0x55.toByte(), f[1])
        assertEquals(2, f[2].toInt()); assertEquals(0, f[3].toInt()) // N = payload + opcode
        assertEquals(0x77, f[4].toInt())
        val crc = ChameleonFrame.crc16(f, 0, 6, 0x0000)
        assertEquals(crc and 0xFF, f[6].toInt() and 0xFF)
        assertEquals(crc ushr 8, f[7].toInt() and 0xFF)
    }

    @Test
    fun parseRoundTripAndCalibration() {
        CrcCalib.override(0x0000)
        val f = ChameleonFrame.build(0x67, byteArrayOf(0x5A))
        val p = ChameleonFrame.parse(f)
        assertTrue(p.crcOk); assertEquals(0x67, p.opcode); assertArrayEquals(byteArrayOf(0x5A), p.payload)
        assertEquals(0x0000, p.crcInitMatched)

        // A frame CRC'd with 0xFFFF must still validate and re-calibrate the start value
        CrcCalib.override(0xFFFF)
        val g = ChameleonFrame.build(0x57, byteArrayOf(1, 2, 3))
        CrcCalib.override(0x0000)
        val q = ChameleonFrame.parse(g)
        assertTrue(q.crcOk); assertEquals(0xFFFF, q.crcInitMatched)
        assertEquals(0xFFFF, CrcCalib.init); assertTrue(CrcCalib.confirmed)

        val bad = g.copyOf().also { it[5] = 9 }
        assertFalse(ChameleonFrame.parse(bad).crcOk)
    }

    @Test
    fun reassemblerHandlesSplitAndConcatenatedChunks() {
        CrcCalib.override(0x0000)
        val a = ChameleonFrame.build(0x77, byteArrayOf(0x05))
        val b = ChameleonFrame.build(0x67, byteArrayOf(0x63))
        val got = mutableListOf<Int>()
        val r = ChameleonFrame.Reassembler { got += it.opcode }
        val all = byteArrayOf(0x00, 0x11) + a + b          // leading junk, then two frames back to back
        r.feed(all.copyOfRange(0, 5))                         // split mid-frame
        r.feed(all.copyOfRange(5, all.size))
        assertEquals(listOf(0x77, 0x67), got)
    }

    @Test
    fun hexHelpers() {
        val bytes = ChameleonFrame.fromHex("aa 55-01:00 77")
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0x55, 0x01, 0x00, 0x77), bytes)
        assertEquals("AA 55 01 00 77", ChameleonFrame.hex(bytes))
    }
}
