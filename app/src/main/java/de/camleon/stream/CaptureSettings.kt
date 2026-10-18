package de.camleon.stream

import android.content.Context
import org.json.JSONObject

/**
 * All user configuration of the capture instance, grouped by the three switchable functions
 * (streaming, recording, tracking) plus the shared video settings.
 *
 * Persisted as one JSON blob in SharedPreferences; the same JSON shape is used by the control API.
 */
data class CaptureSettings(
    // --- Streaming: push to the configured RTMP URL
    val rtmpUrl: String = "",
    val streamAutoStart: Boolean = false,
    // --- Recording: write a local video file on the phone
    val recordAutoStart: Boolean = false,
    // --- Tracking: let the Chameleon track (mode + parameters); auto-start once the gimbal connects
    val trackingAutoStart: Boolean = false,
    val tracking: TrackingSettings = TrackingSettings(),
    // --- Video (shared by stream, recording and preview)
    val quality: Quality = Quality.P720,
    val audioEnabled: Boolean = true,
    // --- Browser preview (WebRTC): cap per viewer, independent of the stream/recording encoder
    val previewQuality: PreviewQuality = PreviewQuality.P540,
    // --- Phone display while capturing (24/7 use: the foreground service keeps camera/stream alive
    //     with the screen off, so the display can be dimmed or allowed to time out)
    val screenMode: ScreenMode = ScreenMode.ON,
) {
    enum class ScreenMode(val label: String) {
        ON("keep screen on"),
        DIM("dim: black screen, tap to wake"),
        TIMEOUT("system timeout: screen turns off by itself"),
    }
    enum class Quality(val label: String, val width: Int, val height: Int, val fps: Int, val bitrateBps: Int) {
        P720("720p · 30fps · 2.5 Mbps", 1280, 720, 30, 2_500_000),
        P1080("1080p · 30fps · 4.5 Mbps", 1920, 1080, 30, 4_500_000),
        P1080_60("1080p · 60fps · 6 Mbps", 1920, 1080, 60, 6_000_000),
    }

    /** Browser preview cap per viewer (always 30 fps; never above the capture resolution). */
    enum class PreviewQuality(val label: String, val height: Int, val bitrateBps: Int) {
        P360("360p · 0.8 Mbps", 360, 800_000),
        P540("540p · 1.5 Mbps", 540, 1_500_000),
        P720("720p · 2.5 Mbps", 720, 2_500_000),
        P1080("1080p · 4 Mbps", 1080, 4_000_000),
    }

    fun isUrlValid(): Boolean =
        Regex("^rtmps?://[\\w.\\-]+(:\\d+)?(/[\\w\\-./?%&=]*)?$", RegexOption.IGNORE_CASE)
            .matches(rtmpUrl.trim())

    fun toJson(): JSONObject = JSONObject().apply {
        put("rtmpUrl", rtmpUrl)
        put("streamAutoStart", streamAutoStart)
        put("recordAutoStart", recordAutoStart)
        put("trackingAutoStart", trackingAutoStart)
        put("quality", quality.name)
        put("audioEnabled", audioEnabled)
        put("previewQuality", previewQuality.name)
        put("screenMode", screenMode.name)
        put("tracking", tracking.toJson())
    }

    /** Apply a partial update from query/JSON parameters (unknown keys ignored, bad values kept as-is). */
    fun applyParams(p: Map<String, String?>): CaptureSettings = copy(
        rtmpUrl = p["url"] ?: p["rtmpUrl"] ?: rtmpUrl,
        streamAutoStart = p["streamAutoStart"]?.toBooleanStrictOrNull() ?: streamAutoStart,
        recordAutoStart = p["recordAutoStart"]?.toBooleanStrictOrNull() ?: recordAutoStart,
        trackingAutoStart = p["trackingAutoStart"]?.toBooleanStrictOrNull() ?: trackingAutoStart,
        quality = p["quality"]?.let { q -> Quality.entries.firstOrNull { it.name == q } } ?: quality,
        audioEnabled = (p["audio"] ?: p["audioEnabled"])?.toBooleanStrictOrNull() ?: audioEnabled,
        previewQuality = p["previewQuality"]?.let { q -> PreviewQuality.entries.firstOrNull { it.name.equals(q, true) } } ?: previewQuality,
        screenMode = p["screenMode"]?.let { m -> ScreenMode.entries.firstOrNull { it.name.equals(m, true) } } ?: screenMode,
        tracking = tracking.applyParams(p),
    )

    companion object {
        private const val PREFS = "capture_settings"
        private const val KEY = "json"

        fun fromJson(o: JSONObject): CaptureSettings = CaptureSettings(
            rtmpUrl = o.optString("rtmpUrl", ""),
            streamAutoStart = o.optBoolean("streamAutoStart", false),
            recordAutoStart = o.optBoolean("recordAutoStart", false),
            trackingAutoStart = o.optBoolean("trackingAutoStart", false),
            quality = runCatching { Quality.valueOf(o.optString("quality", Quality.P720.name)) }.getOrDefault(Quality.P720),
            audioEnabled = o.optBoolean("audioEnabled", true),
            previewQuality = runCatching { PreviewQuality.valueOf(o.optString("previewQuality", PreviewQuality.P540.name)) }.getOrDefault(PreviewQuality.P540),
            screenMode = runCatching { ScreenMode.valueOf(o.optString("screenMode", ScreenMode.ON.name)) }.getOrDefault(ScreenMode.ON),
            tracking = o.optJSONObject("tracking")?.let { TrackingSettings.fromJson(it) } ?: TrackingSettings(),
        )

        fun load(context: Context): CaptureSettings {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return raw?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() } ?: CaptureSettings()
        }

        fun save(context: Context, s: CaptureSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, s.toJson().toString()).apply()
        }
    }
}

/**
 * Tracking configuration: sport/mode plus the gimbal's tracking parameters.
 *
 * Yaw 0° is the hardware zero = the direction the Chameleon faces when switched on; the camera
 * rotation angle is applied symmetrically around it. So: switch the gimbal on pointing at the court
 * centre (or drive it to 0° with "Go to 0°" and align the mount), then set the angle.
 */
data class TrackingSettings(
    /** Sport + variant + age group → Chameleon mode id (see [ChameleonModes]). Default = mode 36. */
    val sport: ChameleonModes.Sport = ChameleonModes.Sport.BASKETBALL,
    val variant: String = "full_high",
    val youth: Boolean = false,
    /** Camera rotation angle ("Kameradrehwinkel") 60..150 — 3rd param of 0x72, gimbal log "yawLim". Applies at tracking start. */
    val yawRange: Int = 85,
    /** Acceleration ("Beschleunigung") 200..1000 — 1st param of 0x72. Applies at tracking start. */
    val acceleration: Int = 1000,
) {
    /** Always a valid combination: unknown variant → the sport's first one; no age split → youth=false. */
    fun normalized(): TrackingSettings {
        val v = sport.variants.firstOrNull { it.key == variant } ?: sport.variants.first()
        return copy(variant = v.key, youth = youth && v.youthId != null)
    }
    fun modeId(): Int {
        val v = sport.variants.firstOrNull { it.key == variant } ?: sport.variants.first()
        return if (youth && v.youthId != null) v.youthId else v.adultId
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("sport", sport.name); put("variant", variant); put("youth", youth); put("modeId", modeId())
        put("yawRange", yawRange); put("acceleration", acceleration)
    }

    fun applyParams(p: Map<String, String?>): TrackingSettings {
        val newSport = p["sport"]?.let { n -> ChameleonModes.Sport.entries.firstOrNull { it.name == n } } ?: sport
        return copy(
            sport = newSport,
            variant = p["variant"] ?: if (newSport != sport) newSport.variants.first().key else variant,
            youth = p["youth"]?.toBooleanStrictOrNull() ?: youth,
            yawRange = p["yawRange"]?.toIntOrNull()?.coerceIn(60, 150) ?: yawRange,
            acceleration = p["acceleration"]?.toIntOrNull()?.coerceIn(200, 1000) ?: acceleration,
        ).normalized()
    }

    companion object {
        fun fromJson(o: JSONObject) = TrackingSettings(
            sport = runCatching { ChameleonModes.Sport.valueOf(o.optString("sport", "BASKETBALL")) }.getOrDefault(ChameleonModes.Sport.BASKETBALL),
            variant = o.optString("variant", "full_high"),
            youth = o.optBoolean("youth", false),
            yawRange = o.optInt("yawRange", 85).coerceIn(60, 150),
            acceleration = o.optInt("acceleration", 1000).coerceIn(200, 1000),
        ).normalized()
    }
}

/**
 * Tracking modes the Chameleon accepts (verified: each id sent with 0x70 and the 0x46 ack checked;
 * rejected ids keep the previous mode). Combination of sport × field × age group × camera height.
 * Rejected by the Chameleon: tennis, pickleball, badminton, follow-me, rugby, flag football,
 * baseball, American football sideline modes (45–47) and basketball KPT (41–44).
 */
object ChameleonModes {
    /** [adultId] = 14 years and older, [youthId] = under 14 (null = no age split). */
    data class Variant(val key: String, val adultId: Int, val youthId: Int?)

    enum class Sport(val variants: List<Variant>) {
        BASKETBALL(listOf(Variant("full_high", 36, 37), Variant("full_low", 5, 6), Variant("half_high", 38, 39), Variant("half_low", 7, 8))),
        SOCCER(listOf(Variant("11v11", 11, 12), Variant("7v7", 3, 4), Variant("5v5", 1, 2))),
        HANDBALL(listOf(Variant("full", 25, 26), Variant("half", 27, 28))),
        ICE_HOCKEY(listOf(Variant("full", 17, 18))),
        LACROSSE(listOf(Variant("full", 15, 16))),
        AMERICAN_FOOTBALL(listOf(Variant("full", 13, 14))),
        VOLLEYBALL(listOf(Variant("full", 40, null))),
    }

    /** Catalogue for the web page: sports with their variants and whether an age split exists. */
    fun toJson(): org.json.JSONArray = org.json.JSONArray().apply {
        Sport.entries.forEach { sp ->
            put(JSONObject().put("id", sp.name).put("variants", org.json.JSONArray().apply {
                sp.variants.forEach { v -> put(JSONObject().put("key", v.key).put("adult", v.adultId).put("youth", v.youthId ?: JSONObject.NULL)) }
            }))
        }
    }
}
