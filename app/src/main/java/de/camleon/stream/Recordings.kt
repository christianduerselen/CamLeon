package de.camleon.stream

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Recorded files for the browser's recordings page: list, open for download / playback, delete.
 *
 * Files live in public Movies/CamLeon (fallback: the app's private Movies/CamLeon). Without a media
 * permission Android shows the app only the files it created itself in this installation; with
 * "Videos" (READ_MEDIA_VIDEO, Android 13+ / READ_EXTERNAL_STORAGE before) older files are listed and
 * readable too, but Android still lets only the creating installation delete them.
 * The file currently being recorded is listed but neither served nor deletable (no MP4 index yet).
 */
class Recordings(private val context: Context, private val currentRecording: () -> String?) {

    sealed class DeleteResult {
        data object Ok : DeleteResult()
        data object NotFound : DeleteResult()
        data object Recording : DeleteResult()
        data object NotAllowed : DeleteResult()
    }

    /** Duration per file, keyed by path + size + mtime (MediaMetadataRetriever is slow for long lists). */
    private val durationCache = HashMap<String, Long?>()

    private fun dirs(): List<File> = listOfNotNull(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER),
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let { File(it, FOLDER) },
    )

    private fun files(): List<File> = dirs()
        .flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".mp4", ignoreCase = true) }?.toList().orEmpty() }
        .distinctBy { it.name }
        .sortedByDescending { it.lastModified() }

    /** Resolve a name from the browser to one of our files; anything with a path component is rejected. */
    fun find(name: String): File? {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.startsWith(".")) return null
        return dirs().map { File(it, name) }.firstOrNull { it.isFile }
    }

    fun isRecording(f: File) = currentRecording() == f.absolutePath

    fun toJson(): JSONObject {
        val arr = JSONArray()
        var total = 0L
        files().forEach { f ->
            val rec = isRecording(f)
            val dur = if (rec) null else duration(f)
            total += f.length()
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("size", f.length())
                put("modified", f.lastModified())
                put("durationMs", dur ?: JSONObject.NULL)
                put("recording", rec)
                // No duration = MP4 without index (recording was cut off) → most players refuse it.
                put("playable", !rec && dur != null)
                put("deletable", !rec && f.canWrite())
            })
        }
        return JSONObject().put("recordings", arr).put("totalBytes", total)
    }

    private fun duration(f: File): Long? = synchronized(durationCache) {
        val key = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
        durationCache.getOrPut(key) {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(f.absolutePath)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
                }
            }.getOrNull()
        }
    }

    fun delete(name: String): DeleteResult {
        val f = find(name) ?: return DeleteResult.NotFound
        if (isRecording(f)) return DeleteResult.Recording
        val path = f.absolutePath
        val ok = f.delete() || runCatching {
            // Own file that the file API refuses: remove it through MediaStore instead.
            context.contentResolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.DATA}=?", arrayOf(path)) > 0
        }.getOrElse { Log.w(TAG, "delete $name: ${it.message}"); false }
        if (!ok || f.exists()) return DeleteResult.NotAllowed
        // Let Gallery / Files forget it too.
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(path), null, null) }
        Log.i(TAG, "deleted $path")
        return DeleteResult.Ok
    }

    private companion object { const val TAG = "Recordings"; const val FOLDER = "CamLeon" }
}
