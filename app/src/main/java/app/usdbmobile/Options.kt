package app.usdbmobile

/** Auswahllisten – entsprechen den Optionen des USDB Syncer (settings.py). */

enum class AudioFormat(val label: String, val codec: String) {
    M4A(".m4a (mp4a)", "m4a"),
    MP3(".mp3 (MPEG)", "mp3"),
    OGG(".ogg (Ogg Vorbis)", "vorbis"),
    OPUS(".opus (Ogg Opus)", "opus");

    fun ytdlFormat() = "bestaudio[ext=${name.lowercase()}]/bestaudio/bestaudio*"
}

enum class VideoContainer(val label: String, val ext: String?, val vcodec: String?) {
    BEST("Beste verfügbare Container/Codec", null, null),
    MP4(".mp4 (bester Codec)", "mp4", null),
    MP4_AV1(".mp4 (AV1-Codec)", "mp4", "av01"),
    MP4_AVC(".mp4 (AVC/H.264-Codec)", "mp4", "^(avc|h264)"),
    MP4_VP9(".mp4 (VP9-Codec)", "mp4", "^vp0?9"),
    WEBM(".webm (VP9-Codec)", "webm", null),
}

object Choices {
    val audioBitrates = listOf(128, 160, 192, 256, 320)
    /** Höhe → Breite */
    val resolutions = listOf(2160 to 3840, 1440 to 2560, 1080 to 1920, 720 to 1280, 480 to 854, 360 to 640)
    val fps = listOf(60, 30)
    /** 0 = unbegrenzt */
    val coverMax = listOf(1920, 1000, 640, 0)
    val backgroundMax = listOf(3840, 1920, 1280, 940, 0)

    fun sizeLabel(v: Int) = if (v == 0) "Aus (Originalgröße)" else "${v}x$v px"
    fun widthFor(height: Int) = resolutions.firstOrNull { it.first == height }?.second ?: (height * 16 / 9)
}

/** Momentaufnahme aller Einstellungen für einen Download. */
data class Options(
    val zip: Boolean,
    val audio: Boolean,
    val audioFormat: AudioFormat,
    val audioKbps: Int,
    val video: Boolean,
    val videoContainer: VideoContainer,
    val maxHeight: Int,
    val maxFps: Int,
    val cover: Boolean,
    val coverMax: Int,
    val background: Boolean,
    val backgroundEvenWithVideo: Boolean,
    val backgroundMax: Int,
) {
    /** yt-dlp-Formatstring wie in download_options.py (VideoOptions.ytdl_format). */
    fun videoFormat(): String {
        val container = videoContainer.ext?.let { "[ext=$it]" } ?: ""
        val codec = videoContainer.vcodec?.let { "[vcodec~='$it']" } ?: ""
        val fmts = mutableListOf("bestvideo*")
        if (container.isNotEmpty()) fmts.add(0, "bestvideo*$container")
        if (codec.isNotEmpty()) fmts.add(0, "bestvideo*$container$codec")
        val w = "[width<=${Choices.widthFor(maxHeight)}]"
        val h = "[height<=$maxHeight]"
        val f = "[fps<=$maxFps]"
        return fmts.joinToString("/") { "$it$w$h$f/$it$w$h/$it" }
    }
}

/** Sortierung der Suche (order/ud wie im USDB-Listenformular). */
enum class Sort(val label: String, val order: String, val desc: Boolean) {
    DATE_DESC("Datum: neu → alt", "id", true),
    DATE_ASC("Datum: alt → neu", "id", false),
    ARTIST_ASC("Interpret A–Z", "interpret", false),
    ARTIST_DESC("Interpret Z–A", "interpret", true),
    TITLE_ASC("Titel A–Z", "title", false),
    TITLE_DESC("Titel Z–A", "title", true),
}
