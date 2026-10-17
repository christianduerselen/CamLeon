package de.camleon.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Minimal, dependency-free GATT client for the Chameleon (Gen2 protocol).
 *
 * Scans for devices advertising the Gen2 service (or whose name hints at XbotGo), connects, requests
 * a larger MTU, enables notifications on AE02 and writes frames to AE01 (chunked to MTU-3). Every
 * byte in and out is mirrored into [ProtocolLog] so the protocol can be studied from the browser.
 */
@SuppressLint("MissingPermission")
class ChameleonBleClient(private val context: Context) {

    enum class ConnState { OFF, DISCONNECTED, SCANNING, CONNECTING, DISCOVERING, READY }

    data class Found(val address: String, val name: String, val rssi: Int, val hasGen2Service: Boolean)

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state.asStateFlow()
    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()
    @Volatile var connectedAddress: String? = null; private set
    @Volatile var connectedName: String? = null; private set
    @Volatile var mtu: Int = 23; private set

    /** Called for every complete, reassembled frame from the gimbal. */
    var onFrame: ((ChameleonFrame.Parsed) -> Unit)? = null

    private val adapter: BluetoothAdapter? get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private val reassembler = ChameleonFrame.Reassembler { f -> ProtocolLog.rx(f); onFrame?.invoke(f) }

    // Simple serialized write queue (one GATT write in flight at a time).
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeBusy = false

    // ---- scanning -------------------------------------------------------------------------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: r.device.name ?: ""
            val hasSvc = r.scanRecord?.serviceUuids?.any { it.uuid == ChameleonFrame.SERVICE } == true
            val interesting = hasSvc || name.contains("xbot", true) || name.contains("chame", true) ||
                name.contains("gimbal", true) || name.contains("blink", true)
            if (!interesting) return
            val f = Found(r.device.address, name, r.rssi, hasSvc)
            _found.value = (_found.value.filter { it.address != f.address } + f).sortedByDescending { it.rssi }
        }
        override fun onScanFailed(errorCode: Int) {
            ProtocolLog.event("scan failed: $errorCode"); scanning = false
            if (_state.value == ConnState.SCANNING) _state.value = ConnState.DISCONNECTED
        }
    }

    fun startScan(durationMs: Long = 12_000) {
        val a = adapter ?: run { _state.value = ConnState.OFF; return }
        if (!a.isEnabled) { _state.value = ConnState.OFF; ProtocolLog.event("bluetooth is off"); return }
        if (scanning) return
        _found.value = emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        a.bluetoothLeScanner.startScan(null, settings, scanCb)
        scanning = true
        if (gatt == null) _state.value = ConnState.SCANNING
        ProtocolLog.event("scan started")
        main.postDelayed({ stopScan() }, durationMs)
    }

    fun stopScan() {
        if (!scanning) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        scanning = false
        if (_state.value == ConnState.SCANNING) _state.value = ConnState.DISCONNECTED
        ProtocolLog.event("scan stopped, ${_found.value.size} candidate(s)")
    }

    // ---- connection -----------------------------------------------------------------------

    fun connect(address: String) {
        val a = adapter ?: return
        stopScan()
        disconnect()
        val dev: BluetoothDevice = runCatching { a.getRemoteDevice(address) }.getOrElse {
            ProtocolLog.event("bad address $address"); return
        }
        _state.value = ConnState.CONNECTING
        ProtocolLog.event("connecting to $address")
        reassembler.reset()
        gatt = dev.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
            ProtocolLog.event("disconnected")
        }
        gatt = null; writeChar = null; connectedAddress = null; connectedName = null; mtu = 23
        synchronized(writeQueue) { writeQueue.clear(); writeBusy = false }
        _state.value = ConnState.DISCONNECTED
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectedAddress = g.device.address; connectedName = g.device.name
                _state.value = ConnState.DISCOVERING
                ProtocolLog.event("connected, requesting MTU 247")
                if (!g.requestMtu(247)) g.discoverServices()
            } else {
                ProtocolLog.event("connection state=$newState status=$status")
                main.post { disconnect() }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            ProtocolLog.event("mtu=$mtu, discovering services")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(ChameleonFrame.SERVICE)
            val names = g.services.joinToString { it.uuid.toString().substring(4, 8) }
            ProtocolLog.event("services: $names")
            if (svc == null) { ProtocolLog.event("Gen2 service AE30 not found"); main.post { disconnect() }; return }
            writeChar = svc.getCharacteristic(ChameleonFrame.WRITE_CHAR)
            val notify = svc.getCharacteristic(ChameleonFrame.NOTIFY_CHAR)
            if (writeChar == null || notify == null) { ProtocolLog.event("AE01/AE02 missing"); main.post { disconnect() }; return }
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(ChameleonFrame.CCCD)
            val useIndicate = notify.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
                notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
            val value = if (useIndicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, value)
                else { @Suppress("DEPRECATION") run { cccd.value = value; g.writeDescriptor(cccd) } }
            } else onReady()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ProtocolLog.event("notifications ${if (status == 0) "enabled" else "FAILED($status)"}")
            onReady()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            ProtocolLog.rxRaw(value); reassembler.feed(value)
        }
        @Deprecated("pre-33") override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) { val v = c.value ?: return; ProtocolLog.rxRaw(v); reassembler.feed(v) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) ProtocolLog.event("write failed status=$status")
            main.postDelayed({ synchronized(writeQueue) { writeBusy = false }; pumpWrites() }, MIN_FRAME_GAP_MS)
        }
    }

    private fun onReady() { _state.value = ConnState.READY; ProtocolLog.event("READY (${connectedName ?: connectedAddress})") }

    // ---- writing --------------------------------------------------------------------------

    /** Send a complete frame (already CRC'd), chunked to the negotiated MTU. */
    fun sendRaw(frame: ByteArray, info: String = "raw"): Boolean {
        if (_state.value != ConnState.READY || writeChar == null) { ProtocolLog.event("send ignored, not ready"); return false }
        val depth = synchronized(writeQueue) { writeQueue.size }
        ProtocolLog.tx(frame, if (depth > 0) "$info q=$depth" else info)   // q = frames still waiting in the BLE queue
        val chunk = (mtu - 3).coerceIn(20, 244)
        synchronized(writeQueue) { frame.toList().chunked(chunk).forEach { writeQueue.addLast(it.toByteArray()) } }
        pumpWrites()
        return true
    }

    /** Build and send `opcode` + `payload` using the calibrated CRC. */
    fun send(opcode: Int, payload: ByteArray = ByteArray(0), info: String = "op 0x%02X".format(opcode)): Boolean =
        sendRaw(ChameleonFrame.build(opcode, payload), info)

    private fun pumpWrites() {
        val g = gatt ?: return; val c = writeChar ?: return
        val next: ByteArray
        synchronized(writeQueue) {
            if (writeBusy || writeQueue.isEmpty()) return
            next = writeQueue.removeFirst(); writeBusy = true
        }
        // Prefer Write Command (no response). A write WITH response blocks the queue for a full round
        // trip per frame; with
        // 10 move frames/s plus stop frames the queue backed up and the stop reached the gimbal late
        // (short tap → movement of a second or more). Fall back to write-with-response only if the
        // characteristic does not allow write-without-response.
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, next, type) == BluetoothGatt.GATT_SUCCESS
                 else { @Suppress("DEPRECATION") run { c.writeType = type; c.value = next; g.writeCharacteristic(c) } }
        if (!ok) { Log.w(TAG, "writeCharacteristic refused"); synchronized(writeQueue) { writeBusy = false } }
        else if (type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            // No callback for no-response writes on some stacks; release the queue after a minimum gap.
            // The gimbal DROPS a frame that arrives back-to-back with the previous one (verified: a
            // pitch stop sent right after a yaw stop was ignored and the pitch ran on for ~1 s, the
            // gimbal's own per-command timeout). ~30 frames/s are fine.
            main.postDelayed({ synchronized(writeQueue) { writeBusy = false }; pumpWrites() }, MIN_FRAME_GAP_MS)
        }
    }

    private companion object { const val TAG = "ChameleonBle"; const val MIN_FRAME_GAP_MS = 35L }
}
