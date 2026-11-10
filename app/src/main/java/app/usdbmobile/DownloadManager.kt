package app.usdbmobile

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed interface Status {
    data object Queued : Status
    data class Working(val step: String, val progress: Float) : Status
    data class Done(val where: String) : Status
    data class Failed(val message: String) : Status
    data object Cancelled : Status
}

val Status.isActive get() = this is Status.Queued || this is Status.Working

/** [opts] ist die Momentaufnahme der Einstellungen zum Zeitpunkt des Tippens auf „Laden“. */
data class Item(val song: Song, val opts: Options, val status: Status)

/**
 * Arbeitet Downloads nacheinander ab. Pro Song (wie im USDB Syncer):
 * Songtext holen → Audio/Video per yt-dlp (Ersatzquellen aus den Kommentaren) →
 * Cover/Hintergrund (Meta-Tags co=/bg= samt Bildbearbeitung) → Header in der .txt anpassen →
 * Ordner (oder ZIP) samt "<id>.usdb" im Zielordner ablegen.
 */
class DownloadManager(private val ctx: Application) {

    val items = MutableStateFlow<List<Item>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Int>(Channel.UNLIMITED)
    private val cancelled = ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var currentId: Int? = null
    @Volatile private var currentProcess: String? = null
    @Volatile private var lastYtError: String? = null

    init {
        scope.launch {
            for (id in queue) {
                if (items.value.firstOrNull { it.song.id == id }?.status is Status.Queued) process(id)
            }
        }
    }

    fun enqueue(song: Song) {
        cancelled.remove(song.id)
        val opts = Graph.prefs.snapshot()
        items.update { list -> list.filterNot { it.song.id == song.id } + Item(song, opts, Status.Queued) }
        queue.trySend(song.id)
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
    }

    fun cancel(id: Int) {
        when (items.value.firstOrNull { it.song.id == id }?.status) {
            is Status.Queued -> set(id, Status.Cancelled)
            is Status.Working -> {
                cancelled += id
                if (currentId == id) currentProcess?.let {
                    try { YoutubeDL.getInstance().destroyProcessById(it) } catch (_: Exception) {}
                }
            }
            else -> {}
        }
    }

    fun remove(id: Int) = items.update { l -> l.filterNot { it.song.id == id && !it.status.isActive } }
    fun clearFinished() = items.update { l -> l.filter { it.status.isActive } }

    private fun set(id: Int, s: Status) =
        items.update { l -> l.map { if (it.song.id == id) it.copy(status = s) else it } }

    private fun checkCancel(id: Int) { if (id in cancelled) throw UserCancelled() }

    private suspend fun process(id: Int) {
        val item = items.value.first { it.song.id == id }
        currentId = id
        try {
            set(id, Status.Done(run(item)))
        } catch (e: UserCancelled) {
            set(id, Status.Cancelled)
        } catch (e: Exception) {
            set(id, Status.Failed(friendly(e)))
        } finally {
            currentId = null; currentProcess = null; cancelled.remove(id)
            File(ctx.cacheDir, "work/$id").deleteRecursively()
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException -> "Keine Internetverbindung."
        else -> e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: "Unbekannter Fehler"
    }

    /** Eine Medienquelle: [resource] wie im Syncer gespeichert (ID/URL aus dem Tag bzw. Kommentar-URL). */
    private class Src(val resource: String, val url: String)

    private class Got(val file: File, val src: Src, val fallback: Boolean)

    private suspend fun run(item: Item): String {
        val id = item.song.id
        val o = item.opts
        fun step(text: String, p: Float) { checkCancel(id); set(id, Status.Working(text, p.coerceIn(0f, 1f))) }

        val tree = Graph.prefs.tree.value
        if (tree.isBlank()) throw IOException("Zielordner fehlt. Bitte unter „Einstellungen“ wählen.")

        step("Songtext laden …", 0.04f)
        var txt = Graph.usdb.fetchTxt(id)
        step("Songdetails laden …", 0.08f)
        val details = Graph.usdb.details(id)

        val artist = TxtTools.header(txt, "ARTIST") ?: item.song.artist
        val title = TxtTools.header(txt, "TITLE") ?: item.song.title
        val base = TxtTools.sanitizeFilename("$artist - $title").take(120).ifBlank { "Song $id" }

        val rawTag = TxtTools.header(txt, "VIDEO")
        val meta = TxtTools.parseVideoTag(rawTag)
        val metaTagsStr = if (rawTag != null && rawTag.contains("=")) rawTag else ""
        val comments = details.commentVideos.take(5).map { Src(it, it) }

        // Quellenlisten (Primärquelle aus dem Tag, danach Links aus den Kommentaren)
        fun primary(res: String?): List<Src> =
            res?.let { r -> TxtTools.toUrl(r)?.let { listOf(Src(r, it)) } } ?: emptyList()
        val audioSources = (primary(meta.audio ?: meta.video) + comments).distinctBy { it.url }
        val videoSources = (primary(meta.video) + comments).distinctBy { it.url }

        val needsMedia = o.audio || o.video
        if (needsMedia) {
            Graph.ytStatus.first { it != "loading" }
            Graph.ytStatus.value.takeIf { it.startsWith("error") }?.let {
                throw IOException("yt-dlp konnte nicht gestartet werden (${it.removePrefix("error: ")}).")
            }
        }
        if (o.audio && audioSources.isEmpty()) {
            throw IOException("Im Songtext und in den Kommentaren ist kein Video-/Audio-Link hinterlegt.")
        }

        val work = File(ctx.cacheDir, "work/$id").apply { deleteRecursively(); mkdirs() }

        // ───── Audio ─────
        val audioEnd = if (o.video) 0.45f else 0.85f
        var audioGot: Got? = null
        if (o.audio) {
            lastYtError = null
            audioGot = download(id, "Audio", audioSources, audioSources.firstOrNull()?.takeIf { primary(meta.audio ?: meta.video).isNotEmpty() },
                work, "audio", 0.10f, audioEnd) { url, out ->
                YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--no-mtime")
                    addOption("-f", o.audioFormat.ytdlFormat())
                    addOption("-x")
                    addOption("--audio-format", o.audioFormat.codec)
                    addOption("--audio-quality", "${o.audioKbps}K")
                    addOption("-o", out)
                }
            } ?: throw IOException(
                "Audio konnte aus keiner Quelle geladen werden" + (lastYtError?.let { ": $it" } ?: ".")
            )
        }

        // ───── Video ─────
        var videoGot: Got? = null
        var videoStatus = "skipped_disabled"
        if (o.video) {
            val audioOnlySong = meta.isAudioOnly() && audioGot?.fallback != true
            if (audioOnlySong || videoSources.isEmpty()) {
                videoStatus = "skipped_unavailable"
            } else {
                lastYtError = null
                videoGot = download(id, "Video", videoSources, videoSources.firstOrNull()?.takeIf { primary(meta.video).isNotEmpty() },
                    work, "video", audioEnd, 0.85f) { url, out ->
                    YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        addOption("--no-mtime")
                        addOption("-f", o.videoFormat())
                        addOption("-o", out)
                    }
                }
                videoStatus = when {
                    videoGot == null -> "failure"
                    videoGot.fallback -> "fallback"
                    else -> "success"
                }
            }
        }

        // ───── Cover (wie _maybe_download_cover) ─────
        var coverFile: File? = null
        var coverRes: String? = null
        var coverStatus = "skipped_disabled"
        if (o.cover) {
            step("Cover laden …", 0.88f)
            meta.cover?.let { tag ->
                val url = tag.sourceUrl()
                fetchImage(url)?.let { Images.toJpeg(it, tag, true, true, o.coverMax) }?.let {
                    coverFile = File(work, "cover.jpg").also { f -> f.writeBytes(it) }
                    coverRes = url; coverStatus = "success"
                }
            }
            if (coverFile == null) {
                details.coverUrl?.let { u ->
                    fetchImage(u)?.let { Images.toJpeg(it, null, true, false, 0) }?.let {
                        coverFile = File(work, "cover.jpg").also { f -> f.writeBytes(it) }
                        coverRes = u; coverStatus = "fallback"
                    }
                }
            }
            if (coverFile == null) coverStatus = "failure"
        }

        // ───── Hintergrund (wie _maybe_download_background) ─────
        var bgFile: File? = null
        var bgRes: String? = null
        var bgStatus = "skipped_disabled"
        if (o.background && (videoGot == null || o.backgroundEvenWithVideo)) {
            step("Hintergrund laden …", 0.91f)
            val tag = meta.background
            if (tag == null) {
                bgStatus = "skipped_unavailable"
            } else {
                val url = tag.sourceUrl()
                fetchImage(url)?.let { Images.toJpeg(it, tag, false, true, o.backgroundMax) }?.let {
                    bgFile = File(work, "background.jpg").also { f -> f.writeBytes(it) }
                    bgRes = url; bgStatus = "success"
                }
                if (bgFile == null) bgStatus = "failure"
            }
        }

        // ───── Dateien benennen, Header anpassen ─────
        step("Dateien vorbereiten …", 0.93f)
        fun rename(f: File?, newName: String): String? {
            if (f == null) return null
            File(work, newName).delete()
            f.renameTo(File(work, newName))
            return newName
        }
        val audioName = rename(audioGot?.file, "$base.${audioGot?.file?.extension}")
        val videoName = rename(videoGot?.file, "$base.${videoGot?.file?.extension}")
        val coverName = rename(coverFile, "$base [CO].jpg")
        val bgName = rename(bgFile, "$base [BG].jpg")

        txt = if (audioName != null) {
            TxtTools.setHeader(TxtTools.setHeader(txt, "MP3", audioName), "AUDIO", audioName)
        } else TxtTools.removeHeader(TxtTools.removeHeader(txt, "MP3"), "AUDIO")
        txt = if (videoName != null) TxtTools.setHeader(txt, "VIDEO", videoName) else TxtTools.removeHeader(txt, "VIDEO")
        txt = if (coverName != null) TxtTools.setHeader(txt, "COVER", coverName) else TxtTools.removeHeader(txt, "COVER")
        txt = if (bgName != null) TxtTools.setHeader(txt, "BACKGROUND", bgName) else TxtTools.removeHeader(txt, "BACKGROUND")
        val txtName = "$base.txt"
        File(work, txtName).writeText(txt, Charsets.UTF_8)

        // ───── .usdb-Inhalt (Syncer-kompatibel) ─────
        val audioStatus = when {
            !o.audio -> "skipped_disabled"
            audioGot?.fallback == true -> "fallback"
            else -> "success"
        }
        val txtUrl = "${UsdbClient.BASE}?link=gettxt&id=$id"
        val buildMeta = { mt: Map<String, Long> ->
            fun r(name: String?, res: String?, status: String) =
                SyncMetaFile.Res(name, name?.let { mt[it] } ?: 0L, res, status)
            SyncMetaFile.build(
                songId = id, usdbMtime = item.song.usdbMtime, metaTags = metaTagsStr,
                txt = r(txtName, txtUrl, "success"),
                audio = r(audioName, audioGot?.src?.resource, audioStatus),
                video = r(videoName, videoGot?.src?.resource, videoStatus),
                cover = r(coverName, coverRes, coverStatus),
                background = r(bgName, bgRes, bgStatus),
            )
        }

        step(if (o.zip) "ZIP erstellen …" else "Speichern …", 0.96f)
        val existing = Graph.library.entries.value[id]
        val files = work.listFiles()!!.sortedBy { it.name }
        val (where, dirDocId) = deliver(tree, base, files, o.zip, existing, buildMeta)

        Graph.library.record(item.song, where, o.zip, dirDocId)

        val notes = buildList {
            if (o.video && videoStatus == "failure") add("ohne Video – keine Quelle ladbar")
            if (o.cover && coverStatus == "failure") add("ohne Cover")
            if (o.cover && coverStatus == "fallback") add("USDB-Cover")
            if (audioGot?.fallback == true) add("Audio aus Kommentar – GAP/BPM prüfen")
        }
        return where + if (notes.isEmpty()) "" else " (${notes.joinToString(", ")})"
    }

    private suspend fun fetchImage(url: String): ByteArray? =
        try { Graph.usdb.downloadBytes(url) } catch (e: Exception) { null }

    /** Probiert die Quellen der Reihe nach; liefert die erste erfolgreich geladene Datei. */
    private fun download(
        id: Int, kind: String, sources: List<Src>, primarySrc: Src?, work: File, prefix: String,
        from: Float, to: Float, build: (String, String) -> YoutubeDLRequest,
    ): Got? {
        for ((i, src) in sources.withIndex()) {
            checkCancel(id)
            work.listFiles { f -> f.name.startsWith("$prefix.") }?.forEach { it.delete() }
            val processId = "dl-$id-$prefix-$i"
            currentProcess = processId
            try {
                val label = "$kind laden …" + if (src !== primarySrc) " (Ersatzquelle)" else ""
                set(id, Status.Working(label, from))
                YoutubeDL.getInstance().execute(build(src.url, File(work, "$prefix.%(ext)s").absolutePath), processId) { pr, _, _ ->
                    set(id, Status.Working(label, from + (to - from) * (pr.coerceAtLeast(0f) / 100f)))
                }
                val f = work.listFiles { f ->
                    f.name.startsWith("$prefix.") && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl")
                }?.maxByOrNull { it.length() }
                if (f != null && f.length() > 0) return Got(f, src, src !== primarySrc)
            } catch (e: Exception) {
                if (id in cancelled) throw UserCancelled()
                lastYtError = e.message?.lines()?.lastOrNull { it.isNotBlank() }
            }
        }
        return null
    }

    // ───────── Ablegen im Zielordner ─────────

    /** @return (Ordnername bzw. ZIP-Name, Dokument-ID des Ordners oder null) */
    private fun deliver(
        treeUri: String, base: String, files: List<File>, zip: Boolean, existing: LibEntry?,
        buildMeta: (Map<String, Long>) -> String,
    ): Pair<String, String?> {
        val tree = Uri.parse(treeUri)
        val root = DocumentFile.fromTreeUri(ctx, tree)
            ?: throw IOException("Zielordner nicht lesbar. Bitte neu wählen.")
        if (!root.canWrite()) throw IOException("Keine Schreibrechte im Zielordner. Bitte neu wählen.")

        if (zip) {
            val metaId = SyncMetaFile.newId()
            val json = buildMeta(files.associate { it.name to it.lastModified() * 1000 })
            root.findFile("$base.zip")?.delete()
            val z = root.createFile("application/zip", "$base.zip")
                ?: throw IOException("ZIP-Datei konnte nicht angelegt werden.")
            ctx.contentResolver.openOutputStream(z.uri)!!.use { os ->
                ZipOutputStream(os.buffered()).use { zos ->
                    for (f in files) {
                        zos.putNextEntry(ZipEntry("$base/${f.name}"))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    zos.putNextEntry(ZipEntry("$base/$metaId.usdb"))
                    zos.write(json.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
            return "$base.zip" to null
        }

        // Vorhandenen Song-Ordner wiederverwenden (z. B. beim Neu-Laden), sonst anlegen
        val reused = existing?.takeIf { !it.zip && it.dirDocId != null }?.let {
            DocumentFile.fromTreeUri(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, it.dirDocId!!))
        }?.takeIf { it.isDirectory && it.canWrite() }
        val dir = reused
            ?: root.findFile(base)?.takeIf { it.isDirectory }
            ?: root.createDirectory(base)
            ?: throw IOException("Ordner konnte nicht angelegt werden.")

        // Bestehende .usdb: ID behalten, alte Dateien später aufräumen
        val oldUsdb = dir.listFiles().firstOrNull { SyncMetaFile.isSyncMetaName(it.name ?: "") }
        val oldNames = oldUsdb?.let {
            try {
                ctx.contentResolver.openInputStream(it.uri)?.use { s -> SyncMetaFile.fileNames(String(s.readBytes(), Charsets.UTF_8)) }
            } catch (e: Exception) { null }
        }.orEmpty()
        val metaName = oldUsdb?.name ?: (SyncMetaFile.newId() + ".usdb")

        val mtimes = HashMap<String, Long>()
        for (f in files) {
            dir.findFile(f.name)?.delete()
            // "application/octet-stream": Android hängt dann keine Endung an den Dateinamen an
            val d = dir.createFile("application/octet-stream", f.name)
                ?: throw IOException("Datei ${f.name} konnte nicht angelegt werden.")
            ctx.contentResolver.openOutputStream(d.uri)!!.use { os -> f.inputStream().use { it.copyTo(os) } }
            mtimes[f.name] = (d.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()) * 1000
        }

        oldUsdb?.delete()
        val m = dir.createFile("application/octet-stream", metaName)
            ?: throw IOException(".usdb-Datei konnte nicht angelegt werden.")
        ctx.contentResolver.openOutputStream(m.uri)!!.use { it.write(buildMeta(mtimes).toByteArray(Charsets.UTF_8)) }

        // Dateien einer früheren Version entfernen, die nicht mehr gebraucht werden (sonst doppelte Songs)
        for (n in oldNames - mtimes.keys - metaName) dir.findFile(n)?.delete()

        return (dir.name ?: base) to DocumentsContract.getDocumentId(dir.uri)
    }
}
