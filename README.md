# CamLeon

Android app for sports recording with the **XbotGo Chameleon** gimbal as hardware: tracking, RTMP
streaming and local recording, plus a browser control page with live preview in the local network.

> CamLeon is an independent private project and is not affiliated with or endorsed by XbotGo.
> Run either CamLeon or another app on the gimbal at a time — both use the same Bluetooth connection.

- Phone (landscape on the Chameleon) runs the app; everything is also controllable from a browser:
  `http://<phone-ip>:8080` (QR code in the app).
- Tracking runs on the gimbal itself; the app talks to it over BLE (protocol: [docs/ble-protocol.md](docs/ble-protocol.md)).
- Streaming/recording: RootEncoder (H.264/AAC), one encoder shared by stream and recording.
  Preview to the browser: WebRTC, capped at 540p / 30 fps / 1.5 Mbps per viewer (each open browser
  tab adds its own encoder on the phone).

## Background operation and quitting

CamLeon keeps running when its window is closed or the screen is locked: a foreground service
(notification "CamLeon is running") holds camera, stream, recording, gimbal link and the browser
control page. Swiping the app away from the recent apps does **not** stop it.

To end CamLeon use **Quit** — in the notification or in the app (bottom right, asks for confirmation
while streaming, recording or tracking). Quit stops tracking, finishes a running recording, stops the
stream, disconnects the gimbal and ends the app. Avoid Android's "Force stop" (or reinstalling) during
a recording: the MP4 is then not finalized and usually does not play.

## Build

Requirements: Android Studio JBR (Java 17), Android SDK 36, .NET SDK with the `nbgv` tool.

```
dotnet tool install -g nbgv            # once
gradlew assembleRelease                # → app/build/outputs/apk/release/app-release.apk
gradlew assembleDebug                  # → app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` must point to the SDK with forward slashes (`sdk.dir=C:/Users/.../Android/Sdk`).

### Versioning (Nerdbank.GitVersioning)

`version.json` holds major.minor.patch. The build asks `nbgv get-version`:

| Build on | versionName | versionCode |
|---|---|---|
| `main` or tag `vX.Y.Z` | `1.0.0` | major·10 000 000 + minor·100 000 + patch·1 000 + git height |
| any other branch | `1.0.0-g<commit>` | same formula |
| debug build type | name + `-debug` | same |

Release: bump `version.json` if needed, commit on `main`, `git tag v1.0.0`, `gradlew assembleRelease`.
Without `nbgv` the build still works but reports `0.0.0-dev`.

### Signing

Release builds are signed with the key referenced by `keystore.properties` in the repo root
(**not** in git). Template:

```
storeFile=C:/_WORK/CamLeon-signing/camleon-release.jks
storePassword=...
keyAlias=camleon
keyPassword=...
```

Keep the keystore and its password backed up: every future update must be signed with the same key,
otherwise Android refuses to install it over the existing app.
Without `keystore.properties` `assembleRelease` produces an unsigned APK.

## Install / update

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

or copy the APK to the phone and open it (allow "install unknown apps" for the file manager once).
Debug builds use the application id `de.camleon.app.debug` and install side by side with the release
(`de.camleon.app`). Run only one of them at a time: both use the camera, the gimbal link and port 8080.

## Debug builds only

- Developer card on the web page: open `http://<phone-ip>:8080/?dev` (protocol tools, raw BLE frames,
  protocol log).
- Developer endpoints (`/api/ble/send|op|crcinit|log`, `/api/webrtc/stats`, `/api/video/orient`) answer
  404 in release builds.

## REST API (all builds, no authentication — LAN only)

```
POST /api/tracking?on=true|false
POST /api/streaming?on=true|false
POST /api/recording?on=true|false
GET  /api/status
POST /api/settings?quality=P1080&audio=true&url=rtmp://...&sport=BASKETBALL&variant=full_high&youth=false&yawRange=85&acceleration=1000
POST /api/gimbal/move?yaw=-100..100&pitch=-100..100      POST /api/gimbal/stop
POST /api/gimbal/home?axis=yaw|pitch                     POST /api/gimbal/home/cancel
GET  /api/recordings                                     GET  /api/recordings/file?name=…[&download=1]
POST /api/recordings/delete?name=…
```

### Recordings

The browser page `http://<phone-ip>:8080/recordings` (button "Recordings" next to the recording switch)
lists the files in `Movies/CamLeon` and lets you play them in the browser, download and delete them.

- `GET /api/recordings` → `{"recordings":[{"name","size","modified","durationMs","recording","playable","deletable"}],"totalBytes"}`.
  `playable: false` marks a file whose recording was cut off (no MP4 index; most players refuse it).
- `GET /api/recordings/file?name=` serves the MP4 with HTTP range support (seeking, resumable
  downloads); `download=1` sends it as an attachment. The file being recorded is not served (409).
- `POST /api/recordings/delete?name=` deletes a file (409 while it is being recorded).
- Without extra permission Android shows CamLeon only the files it recorded in the current
  installation. With the "Videos" permission (asked at start) older files are listed and served too,
  but Android only lets you delete those on the phone (`deletable: false`, delete returns 403).

### Status: phone health (since 1.1.0)

`GET /api/status` contains a `phone` block for remote monitoring (e.g. a stream portal):

```json
"phone": {
  "battery": 87, "powered": true, "thermal": "none",
  "storageFreeBytes": 52345678901, "recordMinutesLeft": 1530
}
```

- `battery`: percent; `powered`: external power connected (also when full or when the phone pauses
  charging). The `gimbal` block uses the same two fields with the same meaning (see below).
- `thermal`: Android thermal status `none`, `light`, `moderate`, `severe`, `critical`, `emergency`,
  `shutdown` (`unknown` if not reported). From `severe` on the phone throttles hard.
- `storageFreeBytes`: free shared storage where `Movies/CamLeon` lives;
  `recordMinutesLeft`: free space at the configured quality (video + audio bitrate).

The `gimbal` block has `battery` (percent, opcode `0x67`) and, since 1.1.0, `powered`
(USB power connected to the gimbal, opcode `0x66`; null until the first report). The gimbal reports
both every 10 s, so a change shows up within about 10 s. Treat `battery` as an estimate: it jumps up
when power is connected and does not drop back right after unplugging
(see [docs/ble-protocol.md](docs/ble-protocol.md)).

## License

MIT — see [LICENSE](LICENSE).
