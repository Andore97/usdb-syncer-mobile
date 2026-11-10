package app.usdbmobile

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ein bereits geladener Song. [name] = Ordnername bzw. ZIP-Dateiname. */
data class LibEntry(
    val song: Song,
    val name: String,
    val zip: Boolean,
    /** Dokument-ID des Song-Ordners (nur nach einem Scan bekannt, nicht gespeichert). */
    val dirDocId: String? = null,
)

/**
 * „Bibliothek“ = alle schon geladenen Songs. Quellen:
 *  1. Song-Ordner im Zielordner, die eine <id>.usdb enthalten (auch vom Desktop-Syncer angelegt),
 *  2. ZIP-Downloads dieser App (in einer ZIP kann man nicht nachsehen, daher eigene Liste).
 */
class Library(private val ctx: Application) {

    val entries = MutableStateFlow<Map<Int, LibEntry>>(emptyMap())
    val scanning = MutableStateFlow(false)
    private val file = File(ctx.filesDir, "library.json")

    init { load() }

    // ───────── Speichern/Laden ─────────

    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            val map = LinkedHashMap<Int, LibEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = Song(
                    id = o.getInt("id"), artist = o.optString("artist"), title = o.optString("title"),
                    genre = o.optString("genre"), year = if (o.has("year")) o.optInt("year") else null,
                    edition = o.optString("edition"), language = o.optString("language"),
                    golden = o.optBoolean("golden"), rating = o.optDouble("rating", 0.0).toFloat(),
                    views = o.optInt("views"), coverUrl = o.optString("cover").ifEmpty { null },
                    usdbMtime = o.optLong("mtime"),
                )
                map[s.id] = LibEntry(s, o.optString("name"), o.optBoolean("zip"))
            }
            entries.value = map
        } catch (_: Exception) {}
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (e in entries.value.values) {
                val s = e.song
                arr.put(JSONObject().apply {
                    put("id", s.id); put("artist", s.artist); put("title", s.title); put("genre", s.genre)
                    s.year?.let { put("year", it) }
                    put("edition", s.edition); put("language", s.language); put("golden", s.golden)
                    put("rating", s.rating.toDouble()); put("views", s.views)
                    s.coverUrl?.let { put("cover", it) }
                    put("mtime", s.usdbMtime); put("name", e.name); put("zip", e.zip)
                })
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {}
    }

    /** Direkt nach einem erfolgreichen Download aufrufen. */
    fun record(song: Song, name: String, zip: Boolean, dirDocId: String? = null) {
        entries.update { it + (song.id to LibEntry(song, name, zip, dirDocId)) }
        save()
    }

    // ───────── Scan des Zielordners ─────────

    private class Child(val docId: String, val name: String, val isDir: Boolean)

    private fun children(tree: Uri, docId: String): List<Child>? = try {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = ArrayList<Child>()
        ctx.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += Child(c.getString(0), c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        } ?: return null
        out
    } catch (e: Exception) { null }

    suspend fun scan() = withContext(Dispatchers.IO) {
        val treeStr = Graph.prefs.tree.value
        if (treeStr.isBlank()) return@withContext
        scanning.value = true
        try {
            val tree = Uri.parse(treeStr)
            val rootKids = children(tree, DocumentsContract.getTreeDocumentId(tree)) ?: return@withContext
            val old = entries.value
            val found = LinkedHashMap<Int, LibEntry>()

            // ZIPs dieser App: gelten als geladen, solange die Datei noch existiert
            val rootNames = rootKids.map { it.name }.toSet()
            for ((id, e) in old) if (e.zip && e.name in rootNames) found[id] = e

            fun walk(dir: Child, kids: List<Child>, depth: Int) {
                val usdb = kids.firstOrNull { !it.isDir && SyncMetaFile.isSyncMetaName(it.name) }
                if (usdb != null) {
                    val json = try {
                        ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, usdb.docId))
                            ?.use { String(it.readBytes(), Charsets.UTF_8) }
                    } catch (e: Exception) { null }
                    val id = json?.let { SyncMetaFile.songId(it) } ?: -1
                    if (id > 0) {
                        val known = old[id]?.song
                        val song = known ?: run {
                            val (a, t) = if (" - " in dir.name) dir.name.split(" - ", limit = 2) else listOf("", dir.name)
                            Song(id, a.trim(), t.trim(), "", null, "", "", false, 0f, 0, null, 0)
                        }
                        found[id] = LibEntry(song, dir.name, false, dir.docId)
                    }
                } else if (depth < 3) {
                    for (sub in kids.filter { it.isDir }) {
                        children(tree, sub.docId)?.let { walk(sub, it, depth + 1) }
                    }
                }
            }
            for (d in rootKids.filter { it.isDir }) {
                children(tree, d.docId)?.let { walk(d, it, 1) }
            }
            entries.value = found
            save()
        } finally {
            scanning.value = false
        }
    }
}
