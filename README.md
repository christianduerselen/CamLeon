# CamLeon

Android app for sports recording with the **XbotGo Chameleon** gimbal as hardware: tracking, RTMP
streaming and local recording, plus a browser control page with live preview in the local network.

> CamLeon is an independent private project and is not affiliated with or endorsed by XbotGo.
> Run either CamLeon or another app on the gimbal at a time — both use the same Bluetooth connection.

- Phone (landscape on the Chameleon) runs the app; everything is also controllable from a browser:
  `http://<phone-ip>:8080` (QR code in the app).
- Tracking runs on the gimbal itself; the app talks to it over BLE (protocol: [docs/ble-protocol.md](docs/ble-protocol.md)).
- Streaming/recording: RootEncoder (H.264/AAC), preview to the browser: WebRTC.

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
(`de.camleon.app`).

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
```

## License

MIT — see [LICENSE](LICENSE).
