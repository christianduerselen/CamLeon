package de.camleon.stream

import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import de.camleon.CaptureSession
import de.camleon.R
import de.camleon.ble.ChameleonBleClient
import de.camleon.web.ControlServer
import de.camleon.common.NetUtil

/**
 * On-device UI. Flow: 1) gimbal link (auto-connects to the remembered gimbal), 2) live preview is
 * always on, 3) the three switches Tracking / Streaming / Recording, 4) gimbal pad, 5) settings.
 * Mirrors exactly what the browser page offers. All texts come from string resources (en/de).
 */
@Composable
fun CaptureScreen() {
    val media = CaptureSession.media
    val settings by CaptureSession.settings.collectAsState()
    val streamState by media.stream.collectAsState()
    val bitrate by media.bitrateKbps.collectAsState()
    val recording by media.recording.collectAsState()
    val recordFile by media.recordFile.collectAsState()
    val tracking by CaptureSession.tracking.collectAsState()
    val bleState by CaptureSession.ble.state.collectAsState()
    val yaw by CaptureSession.gimbalYaw.collectAsState()
    val pitch by CaptureSession.gimbalPitch.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    val connected = bleState == ChameleonBleClient.ConnState.READY

    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1.1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.BottomStart) {
                AndroidView(factory = { ctx -> SurfaceView(ctx).also { media.bindPreview(it) } }, modifier = Modifier.fillMaxSize())
                val overlay = buildList {
                    if (tracking) add(stringResource(R.string.overlay_track))
                    if (media.isStreaming()) add(stringResource(R.string.overlay_live, bitrate))
                    if (recording) add(stringResource(R.string.overlay_rec))
                }.joinToString("  ")
                Text(overlay, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
            }
            val urls = remember { NetUtil.controlUrls(ControlServer.DEFAULT_PORT) }
            var bigQr by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (urls.isNotEmpty()) QrCode(urls.first(), Modifier.size(72.dp).clickable { bigQr = true })
                Text(
                    if (urls.isEmpty()) stringResource(R.string.browser_none) else stringResource(R.string.browser_url, urls.joinToString("  ·  ")),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary,
                )
            }
            if (bigQr && urls.isNotEmpty()) Dialog(onDismissRequest = { bigQr = false }) {
                Card {
                    Column(Modifier.padding(20.dp).clickable { bigQr = false }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        QrCode(urls.first(), Modifier.size(260.dp))
                        Text(urls.first(), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
            GimbalPad(yaw, pitch, connected)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GimbalLinkCard(bleState)

            val trackStatus = (if (connected) modeLabel(settings.tracking) + " · " +
                (if (CaptureSession.gimbalTrackingActive) stringResource(R.string.st_sdk_running) else if (tracking) stringResource(R.string.st_starting) else stringResource(R.string.st_idle))
                else stringResource(R.string.st_connect_first)) + (statusText(CaptureSession.trackingNote)?.let { " · $it" } ?: "")
            SwitchCard(title = stringResource(R.string.tracking), checked = tracking, onToggle = { CaptureSession.setTracking(it) }, enabled = connected, status = trackStatus)
            SwitchCard(
                title = stringResource(R.string.streaming), checked = media.isStreaming() || streamState is MediaPipeline.StreamState.Connecting,
                onToggle = { CaptureSession.setStreaming(it) }, enabled = settings.isUrlValid() || media.isStreaming(),
                status = when (val s = streamState) {
                    MediaPipeline.StreamState.Off -> if (settings.isUrlValid()) settings.rtmpUrl else stringResource(R.string.st_set_url)
                    MediaPipeline.StreamState.Connecting -> stringResource(R.string.st_connecting)
                    MediaPipeline.StreamState.Live -> stringResource(R.string.st_live, bitrate)
                    is MediaPipeline.StreamState.Error -> stringResource(R.string.st_error, s.message)
                },
            )
            SwitchCard(
                title = stringResource(R.string.recording), checked = recording, onToggle = { CaptureSession.setRecording(it) },
                status = if (recording) stringResource(R.string.st_recording_to, recordFile?.substringAfterLast('/') ?: "") else stringResource(R.string.st_saves_to),
            )
            TextButton(onClick = { showSettings = !showSettings }) { Text(stringResource(if (showSettings) R.string.settings_hide else R.string.settings_show)) }
            if (showSettings) SettingsPanel(settings, CaptureSession.locks()) { CaptureSession.updateSettings(it) }
        }
    }
}

@Composable
fun sportLabel(sp: ChameleonModes.Sport): String = stringResource(
    when (sp) {
        ChameleonModes.Sport.BASKETBALL -> R.string.sport_basketball
        ChameleonModes.Sport.SOCCER -> R.string.sport_soccer
        ChameleonModes.Sport.HANDBALL -> R.string.sport_handball
        ChameleonModes.Sport.ICE_HOCKEY -> R.string.sport_ice_hockey
        ChameleonModes.Sport.LACROSSE -> R.string.sport_lacrosse
        ChameleonModes.Sport.AMERICAN_FOOTBALL -> R.string.sport_american_football
        ChameleonModes.Sport.VOLLEYBALL -> R.string.sport_volleyball
    }
)

@Composable
fun variantLabel(key: String): String = when (key) {
    "full_high" -> stringResource(R.string.var_full_high)
    "full_low" -> stringResource(R.string.var_full_low)
    "half_high" -> stringResource(R.string.var_half_high)
    "half_low" -> stringResource(R.string.var_half_low)
    "full" -> stringResource(R.string.var_full)
    "half" -> stringResource(R.string.var_half)
    else -> key.replace("v", " v ")
}

/** e.g. "Basketball · full court, raised camera · 14+" — used in the tracking switch status. */
@Composable
fun modeLabel(t: TrackingSettings): String {
    val v = t.sport.variants.firstOrNull { it.key == t.variant }
    val parts = mutableListOf(sportLabel(t.sport))
    if (t.sport.variants.size > 1) parts += variantLabel(t.variant)
    if (v?.youthId != null) parts += stringResource(if (t.youth) R.string.age_youth else R.string.age_adult)
    return parts.joinToString(" · ")
}

/**
 * Status messages are produced by the session in English (they also go to the web API). The phone
 * UI maps the known ones to resources; unknown texts pass through unchanged.
 */
@Composable
fun statusText(msg: String?): String? {
    if (msg == null) return null
    return when {
        msg == "preparing gimbal…" -> stringResource(R.string.msg_preparing)
        msg == "gimbal not connected — tracking will apply once connected" -> stringResource(R.string.msg_not_connected_tracking)
        msg == "waiting for the gimbal to settle…" -> stringResource(R.string.msg_settle)
        msg == "gimbal did not confirm SDK start — try again" -> stringResource(R.string.msg_no_confirm)
        msg == "gimbal not connected" -> stringResource(R.string.msg_gimbal_not_connected)
        msg == "no gimbal angle yet" -> stringResource(R.string.msg_no_angle)
        msg == "ready" -> stringResource(R.string.phase_ready)
        msg == "idle" -> stringResource(R.string.phase_idle)
        msg == "bluetooth off" -> stringResource(R.string.phase_bt_off)
        msg == "searching for gimbal" -> stringResource(R.string.phase_searching)
        msg.startsWith("disconnected by user") -> stringResource(R.string.phase_paused)
        msg.startsWith("connecting to ") -> stringResource(R.string.phase_connecting_to, msg.removePrefix("connecting to "))
        msg.startsWith("waiting (") -> stringResource(R.string.phase_waiting, msg.removePrefix("waiting "))
        msg.startsWith("centre NOT re-based") -> stringResource(R.string.msg_rebase_failed, Regex("""yaw (-?[\d.,]+)""").find(msg)?.groupValues?.get(1) ?: "?")
        else -> msg
    }
}

/** Step 1: the gimbal link. Auto-connects to the remembered gimbal; manual pick when several are in range. */
@Composable
private fun GimbalLinkCard(bleState: ChameleonBleClient.ConnState) {
    val phase by CaptureSession.link.phase.collectAsState()
    val remembered by CaptureSession.link.remembered.collectAsState()
    val auto by CaptureSession.link.autoConnect.collectAsState()
    val found by CaptureSession.ble.found.collectAsState()
    val ready = bleState == ChameleonBleClient.ConnState.READY
    if (ready) {
        // Connected: one slim status line so the live content stays in focus.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("●", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(R.string.gimbal_named, CaptureSession.ble.connectedName ?: CaptureSession.ble.connectedAddress ?: stringResource(R.string.connected)),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { CaptureSession.bleDisconnect() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text(stringResource(R.string.disconnect), style = MaterialTheme.typography.labelSmall)
            }
        }
        return
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.gimbal), style = MaterialTheme.typography.titleMedium)
                    Text(
                        (statusText(phase) ?: "") + "  " + (remembered?.let { stringResource(R.string.last_gimbal, it.name ?: it.address) } ?: stringResource(R.string.no_gimbal_remembered)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = { CaptureSession.reconnectNow() }) { Text(stringResource(R.string.connect_now)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.auto_connect), style = MaterialTheme.typography.bodySmall)
                Switch(checked = auto, onCheckedChange = { CaptureSession.setAutoConnect(it) })
                if (remembered != null) TextButton(onClick = { CaptureSession.forgetGimbal() }) { Text(stringResource(R.string.forget)) }
                TextButton(onClick = { CaptureSession.bleScan() }) { Text(stringResource(R.string.scan)) }
            }
            if (found.isNotEmpty()) {
                found.take(5).forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${d.name.ifBlank { stringResource(R.string.no_name) }}${if (d.hasGen2Service) " · AE30" else ""} · ${d.rssi} dBm",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { CaptureSession.bleConnect(d.address) }) { Text(stringResource(R.string.connect)) }
                    }
                }
            }
        }
    }
}

/** Position line (each axis with its own "→ 0°") + manual pan/tilt pad (hold to move; also tracking override). */
@Composable
private fun GimbalPad(yaw: Double?, pitch: Double?, connected: Boolean) {
    var speed by remember { mutableStateOf(50f) }
    val motion by CaptureSession.motion.collectAsState()
    fun f(v: Double?) = v?.let { "%.1f°".format(it) } ?: "—"
    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("◀▶", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.axis_yaw), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(f(yaw), style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
                TextButton(onClick = { CaptureSession.goHome(true, false) }, enabled = connected && !CaptureSession.homing, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text(stringResource(R.string.to_zero)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("▲▼", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.axis_pitch), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(f(pitch), style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
                TextButton(onClick = { CaptureSession.goHome(false, true) }, enabled = connected && !CaptureSession.homing, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text(stringResource(R.string.to_zero)) }
            }
            if (!connected) Text(stringResource(R.string.not_connected), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (CaptureSession.homing) {
                Text(stringResource(R.string.driving_to_zero), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { CaptureSession.cancelHome() }) { Text(stringResource(R.string.cancel)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(170.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HoldButton("▲") { down -> CaptureSession.move(0, if (down) speed.toInt() else 0) }
                Row {
                    HoldButton("◀") { down -> CaptureSession.move(if (down) -speed.toInt() else 0, 0) }
                    // Centre = what the gimbal is being told right now: a dot when idle, the arrow while moving.
                    val moving = motion.first != 0 || motion.second != 0
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        Text(motionSymbol(motion.first, motion.second), style = MaterialTheme.typography.headlineSmall,
                            color = if (moving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HoldButton("▶") { down -> CaptureSession.move(if (down) speed.toInt() else 0, 0) }
                }
                HoldButton("▼") { down -> CaptureSession.move(0, if (down) -speed.toInt() else 0) }
                Text(stringResource(R.string.speed, speed.toInt()), style = MaterialTheme.typography.labelSmall)
                Slider(value = speed, onValueChange = { speed = it }, valueRange = 10f..100f, modifier = Modifier.fillMaxWidth())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.pad_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                statusText(CaptureSession.gimbalNote)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
      }
    }
}

/** Arrow for a commanded motion (image semantics: pitch > 0 = camera up); a dot when idle. */
private fun motionSymbol(y: Int, p: Int): String = when {
    y == 0 && p == 0 -> "●"
    y > 0 && p > 0 -> "↗"; y < 0 && p > 0 -> "↖"; y > 0 && p < 0 -> "↘"; y < 0 && p < 0 -> "↙"
    y > 0 -> "▶"; y < 0 -> "◀"; p > 0 -> "▲"; else -> "▼"
}

@Composable
private fun HoldButton(label: String, onHold: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    // keep-alive while held (the session stops the motion by itself if these stop arriving)
    androidx.compose.runtime.LaunchedEffect(pressed) {
        while (pressed) { kotlinx.coroutines.delay(300); if (pressed) onHold(true) }
    }
    Box(
        Modifier.size(52.dp).padding(3.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                pressed = true; onHold(true)
                try { tryAwaitRelease() } finally { pressed = false; onHold(false) }
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun SwitchCard(title: String, checked: Boolean, onToggle: (Boolean) -> Unit, status: String, enabled: Boolean = true) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
        }
    }
}

@Composable
private fun SettingsPanel(s: CaptureSettings, lk: CaptureSession.Locks, onChange: (CaptureSettings) -> Unit) {
    var url by remember(s.rtmpUrl) { mutableStateOf(s.rtmpUrl) }
    var qualityMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var variantMenu by remember { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    @Composable
    fun Header(title: String, locked: Boolean, lockedBy: String) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (locked) Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.locked_while, lockedBy), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ---------------- Video (shared by streaming + recording) ----------------
            val videoLockedBy = if (lk.streaming && lk.recording) stringResource(R.string.streaming) + " / " + stringResource(R.string.recording)
                else if (lk.streaming) stringResource(R.string.streaming) else stringResource(R.string.recording)
            Header(stringResource(R.string.video_section), lk.video, videoLockedBy)
            Box {
                FilterChip(selected = true, enabled = !lk.video, onClick = { qualityMenu = true }, label = { Text(s.quality.label) })
                DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                    CaptureSession.supportedQualities().forEach { q ->
                        DropdownMenuItem(text = { Text(q.label) }, onClick = { onChange(s.copy(quality = q)); qualityMenu = false })
                    }
                }
            }
            ToggleRow(stringResource(R.string.audio), s.audioEnabled, enabled = !lk.video) { onChange(s.copy(audioEnabled = it)) }
            HorizontalDivider()

            // ---------------- Tracking ----------------
            Header(stringResource(R.string.tracking), lk.tracking, stringResource(R.string.tracking_runs))
            // Sport → variant (field / camera height) → age group; only modes the Chameleon accepts.
            val t = s.tracking
            val curVariant = t.sport.variants.firstOrNull { it.key == t.variant } ?: t.sport.variants.first()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    FilterChip(selected = true, enabled = !lk.tracking, onClick = { modeMenu = true }, label = { Text(sportLabel(t.sport)) })
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        ChameleonModes.Sport.entries.forEach { sp ->
                            DropdownMenuItem(text = { Text(sportLabel(sp)) }, onClick = {
                                onChange(s.copy(tracking = t.copy(sport = sp, variant = sp.variants.first().key).normalized())); modeMenu = false })
                        }
                    }
                }
                if (t.sport.variants.size > 1) Box {
                    FilterChip(selected = true, enabled = !lk.tracking, onClick = { variantMenu = true }, label = { Text(variantLabel(t.variant)) })
                    DropdownMenu(expanded = variantMenu, onDismissRequest = { variantMenu = false }) {
                        t.sport.variants.forEach { v ->
                            DropdownMenuItem(text = { Text(variantLabel(v.key)) }, onClick = { onChange(s.copy(tracking = t.copy(variant = v.key).normalized())); variantMenu = false })
                        }
                    }
                }
            }
            if (curVariant.youthId != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !t.youth, enabled = !lk.tracking, onClick = { onChange(s.copy(tracking = t.copy(youth = false))) }, label = { Text(stringResource(R.string.age_adult)) })
                FilterChip(selected = t.youth, enabled = !lk.tracking, onClick = { onChange(s.copy(tracking = t.copy(youth = true))) }, label = { Text(stringResource(R.string.age_youth)) })
            }
            Column {
                Text(stringResource(R.string.rot_angle, s.tracking.yawRange), style = MaterialTheme.typography.bodyMedium)
                Slider(value = s.tracking.yawRange.toFloat(), onValueChange = { onChange(s.copy(tracking = s.tracking.copy(yawRange = it.toInt()))) }, valueRange = 60f..150f, steps = 17, enabled = !lk.tracking)
            }
            Column {
                Text(stringResource(R.string.accel, s.tracking.acceleration), style = MaterialTheme.typography.bodyMedium)
                Slider(value = s.tracking.acceleration.toFloat(), onValueChange = { onChange(s.copy(tracking = s.tracking.copy(acceleration = (it / 50).toInt() * 50))) }, valueRange = 200f..1000f, steps = 15, enabled = !lk.tracking)
            }
            ToggleRow(stringResource(R.string.track_auto), s.trackingAutoStart, enabled = !lk.tracking) { onChange(s.copy(trackingAutoStart = it)) }
            Text(stringResource(R.string.angle_hint), style = MaterialTheme.typography.bodySmall, color = dim)
            HorizontalDivider()

            // ---------------- Streaming ----------------
            Header(stringResource(R.string.streaming), lk.streaming, stringResource(R.string.streaming))
            OutlinedTextField(
                value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.rtmp_url)) }, singleLine = true, enabled = !lk.streaming,
                isError = url.isNotBlank() && !s.copy(rtmpUrl = url).isUrlValid(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth(),
                trailingIcon = { if (url != s.rtmpUrl && !lk.streaming) TextButton(onClick = { onChange(s.copy(rtmpUrl = url.trim())) }) { Text(stringResource(R.string.save)) } },
            )
            ToggleRow(stringResource(R.string.stream_auto), s.streamAutoStart, enabled = !lk.streaming) { onChange(s.copy(streamAutoStart = it)) }
            HorizontalDivider()

            // ---------------- Recording ----------------
            Header(stringResource(R.string.recording), lk.recording, stringResource(R.string.recording))
            ToggleRow(stringResource(R.string.rec_auto), s.recordAutoStart, enabled = !lk.recording) { onChange(s.copy(recordAutoStart = it)) }
            Text(stringResource(R.string.files_hint), style = MaterialTheme.typography.bodySmall, color = dim)
            HorizontalDivider()

            // ---------------- Phone display (never locked) ----------------
            Header(stringResource(R.string.display_section), false, "")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CaptureSettings.ScreenMode.entries.forEach { m ->
                    FilterChip(selected = s.screenMode == m, onClick = { onChange(s.copy(screenMode = m)) },
                        label = { Text(stringResource(when (m) { CaptureSettings.ScreenMode.ON -> R.string.scr_on; CaptureSettings.ScreenMode.DIM -> R.string.scr_dim; CaptureSettings.ScreenMode.TIMEOUT -> R.string.scr_timeout })) })
                }
            }
            Text(stringResource(R.string.display_hint), style = MaterialTheme.typography.bodySmall, color = dim)
            HorizontalDivider()
            Text("CamLeon " + de.camleon.BuildConfig.VERSION_NAME + if (de.camleon.BuildConfig.DEBUG) " · debug" else "",
                style = MaterialTheme.typography.labelSmall, color = dim)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** QR code of [text] (white background, black modules), rendered crisp without filtering. */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) { qrBitmap(text, 384) }
    Image(bitmap = bmp.asImageBitmap(), contentDescription = text, modifier = modifier, filterQuality = FilterQuality.None)
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
