# Chameleon BLE protocol (as used by CamLeon)

Notes on the Bluetooth LE protocol of the XbotGo Chameleon gimbal, as observed and used by CamLeon.
Everything here was verified against the hardware; other firmware versions may behave differently.

## Connection

- The gimbal advertises with the name `XbotGo-xxxxxx` and no service UUIDs.
- GATT service `0000AE30-…`, write characteristic `0000AE01-…`, notify characteristic `0000AE02-…`.
- An MTU of 517 is accepted. Writes use *write without response*.
- **Frames must be paced:** a frame that arrives back-to-back with the previous one may be dropped.
  CamLeon keeps at least 35 ms between frames.

## Frame format

```
AA 55 | len (u16 LE) | opcode | payload … | CRC16 (LE)
```

- `len` = payload length + 1 (opcode).
- CRC-16, polynomial 0x1021 (MSB first), initial value 0x0000, over all bytes before the CRC.
- Unknown or empty requests are echoed back unchanged.

## Phone → gimbal

| Opcode | Payload | Meaning |
|---|---|---|
| `0x41` | `axis u8, dir u8, speed u16, 250 u16` | Relative move. axis 0 = yaw, 1 = pitch. dir `'f'` (0x66) / `'b'` (0x62) / `'s'` (0x73 = stop, speed 0). Speed 100…1000. A single frame moves for about 1 s; repeat while moving and send a stop when done. |
| `0x7A` | `01` / `00` | Angle reporting on/off (starts the `0x42` stream). |
| `0x70` | `mode u8` | Select tracking mode (see below). Acknowledged with `0x46`. |
| `0x72` | `acceleration u16, 0 u16, yawLimit u16, 0 u16` | Tracking parameters: acceleration 200…1000, camera rotation angle (yaw limit) 60…150°. Only effective before tracking starts. |
| `0x6F` | `01` / `00` | Start / stop tracking (the tracking runs on the gimbal). |
| `0x76 01`, `0x58 01`, `0x62 01`, `0x6B 08`, `0x47 01` | | Sent once after connecting. |

Directions: yaw `'f'` increases the yaw angle (camera turns right), pitch `'b'` tilts the camera up.

### Starting tracking

1. `0x70 <mode>`, then wait about 400 ms
2. `0x72 <params>`, then wait about 350 ms
3. `0x6F 01`, acknowledged with `0x46 <mode> 01 01`

Sent without the pauses, the start is ignored.

### Tracking modes accepted by the Chameleon

The first id is "14 years and older", the second "under 14".

| Sport | Variant | Mode ids |
|---|---|---|
| Basketball | full court, raised camera | 36 / 37 |
| Basketball | full court, low camera | 5 / 6 |
| Basketball | half court, raised camera | 38 / 39 |
| Basketball | half court, low camera | 7 / 8 |
| Soccer | 11 v 11 / 7 v 7 / 5 v 5 | 11 / 12, 3 / 4, 1 / 2 |
| Handball | full / half field | 25 / 26, 27 / 28 |
| Ice hockey | full field | 17 / 18 |
| Lacrosse | full field | 15 / 16 |
| American football | full field | 13 / 14 |
| Volleyball | — | 40 |

Other ids are rejected (the gimbal keeps the previous mode).

## Gimbal → phone

| Opcode | Payload | Meaning |
|---|---|---|
| `0x42` | `yaw i16, pitch i16` (× 0.01°) | Angles, sent while reporting is on and tracking is off. |
| `0x46` | `mode, capture, track` | Acknowledgement for `0x70` / `0x72` / `0x6F`. |
| `0x67` | `percent` | Battery level. |
| `0x78` | ASCII text | Log lines of the tracking engine. While tracking runs there is no `0x42`; the per-frame lines (`FrameN … YawX PitY`) carry the angles instead. |
| `0x52`, `0x66`, `0x6D` | | Periodic status / capability table (not used). |

## Behaviour worth knowing

- **Yaw zero follows the tracking start:** on `0x6F 01` the gimbal takes its current direction as yaw 0°;
  the camera rotation angle applies ± around it. On `0x6F 00` it drives back to yaw 0° and pitch 0°.
- **First start after connecting:** the first tracking start on a new BLE connection keeps the old yaw
  zero. A short start/stop cycle right after connecting avoids this (CamLeon does it automatically).
- At tracking start the pitch moves to a mode-specific initial angle (about 6°).
- There is no absolute-angle command; "go to 0°" is a feedback loop over relative moves.
- A BLE disconnect ends a running tracking session.
