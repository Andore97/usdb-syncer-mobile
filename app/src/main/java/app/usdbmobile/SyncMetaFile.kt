package app.usdbmobile

import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Random

/**
 * Schreibt die "<id>.usdb"-Datei im Format des USDB Syncer (sync_meta.py, Version 1).
 * Der Syncer erkennt Songs daran als „schon geladen“; die App liest dieselbe Datei.
 */
object SyncMetaFile {

    /** Eine Ressource (txt/audio/video/cover/background). fname = null → nur Status. */
    class Res(val fname: String?, val mtimeMicros: Long, val resource: String?, val status: String)

    /** 11 Zeichen: 8 zufällige Bytes, URL-sicheres Base64 ohne Padding (wie SyncMetaId). */
    fun newId(): String {
        val bytes = ByteBuffer.allocate(8).putLong(Random().nextLong()).array()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun isSyncMetaName(name: String): Boolean =
        name.endsWith(".usdb", ignoreCase = true) && name.length == 11 + 5

    private fun res(r: Res?): Any {
        if (r == null) return JSONObject.NULL
        return JSONObject().apply {
            if (r.fname != null) {
                put("fname", r.fname)
                put("mtime", r.mtimeMicros)
                put("resource", r.resource ?: "")
            }
            put("status", r.status)
        }
    }

    fun build(
        songId: Int, usdbMtime: Long, metaTags: String,
        txt: Res, audio: Res, video: Res, cover: Res, background: Res,
    ): String = JSONObject().apply {
        put("song_id", songId)
        put("usdb_mtime", usdbMtime)
        put("meta_tags", metaTags)
        put("pinned", false)
        put("txt", res(txt))
        put("audio", res(audio))
        put("vocals", res(Res(null, 0, null, "skipped_disabled")))
        put("instrumental", res(Res(null, 0, null, "skipped_disabled")))
        put("video", res(video))
        put("cover", res(cover))
        put("background", res(background))
        put("custom_data", JSONObject())
        put("version", 1)
    }.toString(4)

    /** Liest die Dateinamen der Ressourcen aus einer bestehenden .usdb (zum Aufräumen beim Neu-Laden). */
    fun fileNames(json: String): Set<String> = try {
        val o = JSONObject(json)
        listOf("txt", "audio", "vocals", "instrumental", "video", "cover", "background")
            .mapNotNull { o.optJSONObject(it)?.optString("fname", "")?.takeIf { n -> n.isNotEmpty() } }
            .toSet()
    } catch (e: Exception) { emptySet() }

    fun songId(json: String): Int = try { JSONObject(json).optInt("song_id", -1) } catch (e: Exception) { -1 }
}
