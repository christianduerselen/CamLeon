package de.camleon

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.camleon.stream.CaptureScreen
import de.camleon.stream.CaptureSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color

/**
 * Capture app (App 1): phone mounted (landscape) on the Chameleon gimbal.
 * Three switches — Streaming, Recording, Tracking — plus settings; the same model is exposed to
 * the browser via the LAN control server. Streaming continues when this UI is closed.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenMode(CaptureSettings.ScreenMode.ON)
        CaptureSession.init(this)

        setContent {
            MaterialTheme {
                // safeDrawing = status bar + navigation bar (vertical on the right in landscape) + the
                // front-camera cutout: nothing interactive is drawn underneath them any more.
                val settings by CaptureSession.settings.collectAsState()
                Surface(Modifier.fillMaxSize()) {
                  Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    var granted by remember { mutableStateOf(hasMediaPermissions()) }
                    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
                        granted = (r[Manifest.permission.CAMERA] == true && r[Manifest.permission.RECORD_AUDIO] == true) || hasMediaPermissions()
                    }
                    if (granted) {
                        remember {
                            CaptureSession.ensureCameraStarted()
                            CaptureService.start(this@MainActivity)   // foreground: survives screen lock
                            CaptureSession.onReady()          // applies the auto-start switches once
                        }
                        LaunchedEffect(settings.screenMode) { applyScreenMode(settings.screenMode) }
                        CaptureScreen()
                    } else {
                        PermissionGate { launcher.launch(requiredPermissions()) }
                    }
                  }
                  if (settings.screenMode == CaptureSettings.ScreenMode.DIM && hasMediaPermissions()) {
                      // Full screen (incl. the cutout / status bar / nav bar areas), black, minimum
                      // brightness (≈ off on AMOLED). Any tap returns to "keep on".
                      Box(
                          Modifier.fillMaxSize().background(Color.Black)
                              .clickable { CaptureSession.updateSettings(settings.copy(screenMode = CaptureSettings.ScreenMode.ON)) },
                          contentAlignment = Alignment.BottomEnd,
                      ) { Text(stringResource(R.string.dim_hint), color = Color.DarkGray, modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) }
                  }
                }
            }
        }
    }

    /** Window side of [CaptureSettings.ScreenMode]: keep-on flag and brightness override. */
    private fun applyScreenMode(mode: CaptureSettings.ScreenMode) {
        val lp = window.attributes
        when (mode) {
            CaptureSettings.ScreenMode.ON -> { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            CaptureSettings.ScreenMode.DIM -> { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); lp.screenBrightness = 0.0f }
            CaptureSettings.ScreenMode.TIMEOUT -> { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
        window.attributes = lp
        // DIM: hide status + navigation bar too (their icons would stay lit); a swipe shows them briefly.
        val ctl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (mode == CaptureSettings.ScreenMode.DIM) {
            ctl.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else ctl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the lock screen / another app: if the camera was taken away meanwhile,
        // get it back right now instead of waiting for the next retry tick.
        if (hasMediaPermissions()) runCatching { CaptureSession.camera.recoverIfLost() }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun hasMediaPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.perm_text))
        Button(onClick = onRequest) { Text(stringResource(R.string.perm_button)) }
    }
}
