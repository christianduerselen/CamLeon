package de.camleon.common

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import org.json.JSONObject

/**
 * Phone health for `/api/status` (block `phone`): battery level and external power, Android thermal
 * status (throttling), free storage on the volume recordings are written to.
 *
 * Read on every status request; all sources are cheap (sticky battery broadcast, PowerManager, StatFs),
 * the web page polls once per second.
 */
object PhoneStatus {

    /** [recordBitrateBps]: video + audio bitrate of the configured quality, for the remaining recording time. */
    fun toJson(context: Context, recordBitrateBps: Long): JSONObject = JSONObject().apply {
        // ---- battery: sticky ACTION_BATTERY_CHANGED, no receiver needed
        val bat = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        if (bat != null) {
            val level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            put("battery", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            // powered: external power connected (also when full or when the phone pauses charging to
            // protect the battery) — same meaning as gimbal.powered.
            put("powered", bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }

        // ---- thermal status (API 29+): from "none" up to "shutdown"; from "severe" on Android throttles hard
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        put("thermal", when (pm?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        })

        // ---- storage: shared external storage holds Movies/CamLeon (and the app-dir fallback)
        runCatching {
            @Suppress("DEPRECATION")
            val fs = StatFs(Environment.getExternalStorageDirectory().path)
            val free = fs.availableBytes
            put("storageFreeBytes", free)
            if (recordBitrateBps > 0) put("recordMinutesLeft", free * 8 / recordBitrateBps / 60)
        }
    }
}
