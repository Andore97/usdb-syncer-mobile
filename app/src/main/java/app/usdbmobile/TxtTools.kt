package app.usdbmobile

/** Hilfen rund um die UltraStar-.txt und das (überladene) #VIDEO-Tag. */
object TxtTools {

    /** Wert eines Headers (#KEY:wert) oder null. */
    fun header(txt: String, key: String): String? {
        val prefix = "#${key.uppercase()}:"
        for (line in txt.lineSequence()) {
            if (!line.startsWith("#")) break
            if (line.uppercase().startsWith(prefix)) return line.substring(prefix.length).trim()
        }
        return null
    }

    /** Setzt einen Header (ersetzt vorhandenen oder fügt ihn am Ende des Kopfbereichs ein). */
    fun setHeader(txt: String, key: String, value: String): String {
        val nl = if (txt.contains("\r\n")) "\r\n" else "\n"
        val lines = txt.split(nl).toMutableList()
        val prefix = "#${key.uppercase()}:"
        val newLine = "#${key.uppercase()}:$value"
        var lastHeader = -1
        for ((i, l) in lines.withIndex()) {
            if (!l.startsWith("#")) break
            lastHeader = i
            if (l.uppercase().startsWith(prefix)) {
                lines[i] = newLine
                return lines.joinToString(nl)
            }
        }
        lines.add(lastHeader + 1, newLine)
        return lines.joinToString(nl)
    }

    fun removeHeader(txt: String, key: String): String {
        val nl = if (txt.contains("\r\n")) "\r\n" else "\n"
        val prefix = "#${key.uppercase()}:"
        val out = ArrayList<String>()
        var inHeader = true
        for (l in txt.split(nl)) {
            if (inHeader && !l.startsWith("#")) inHeader = false
            if (inHeader && l.uppercase().startsWith(prefix)) continue
            out.add(l)
        }
        return out.joinToString(nl)
    }

    // ───────── Meta-Tags (meta_tags.py) ─────────

    /** Bild-Meta-Tags: co=/bg= samt rotate, crop, resize, contrast. */
    class ImageTag(
        val source: String,
        var rotate: Float? = null,
        /** left, upper, right, lower */
        var crop: IntArray? = null,
        var resize: Pair<Int, Int>? = null,
        /** "auto" oder Zahl als Text */
        var contrast: String? = null,
    ) {
        fun sourceUrl(): String = when {
            "://" in source -> source.replace("images.fanart.tv", "assets.fanart.tv")
            "/" in source -> "https://$source"
            else -> "https://assets.fanart.tv/fanart/$source"
        }

        fun hasProcessing() = (rotate != null && rotate != 0f) || crop != null || resize != null ||
            (contrast != null && contrast != "0" && contrast != "0.0")
    }

    class MetaTags(
        val video: String?,
        val audio: String?,
        val cover: ImageTag?,
        val background: ImageTag?,
    ) {
        fun isAudioOnly() = audio != null && video == null
    }

    /** Parst z. B. "v=abcDEF12345,a=xyz,co=...,co-resize=1000" – sonst (normaler Dateiname) leer. */
    fun parseVideoTag(tag: String?): MetaTags {
        if (tag == null || !tag.contains("=")) return MetaTags(null, null, null, null)
        var v: String? = null
        var a: String? = null
        var co: ImageTag? = null
        var bg: ImageTag? = null
        for (pair in tag.split(",")) {
            if (!pair.contains("=")) continue
            val parts = pair.trimStart().split("=", limit = 2)
            val key = parts[0].lowercase()
            val value = parts[1].replace("%2C", ",")
            when (key) {
                "v" -> v = value.substringBefore("&")
                "a" -> a = value.substringBefore("&")
                "co" -> co = ImageTag(value)
                "bg" -> bg = ImageTag(value)
                "co-rotate" -> co?.rotate = value.toFloatOrNull()
                "co-crop" -> co?.crop = parseCrop(value)
                "co-resize" -> co?.resize = parseResize(value)
                "co-contrast" -> co?.contrast = if (value == "auto" || value.toFloatOrNull() != null) value else null
                "bg-crop" -> bg?.crop = parseCrop(value)
                "bg-resize" -> bg?.resize = parseResize(value)
            }
        }
        return MetaTags(v, a, co, bg)
    }

    /** "links-oben-breite-höhe" → (left, upper, right, lower) */
    private fun parseCrop(value: String): IntArray? {
        val p = value.split("-").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return null
        return intArrayOf(p[0], p[1], p[0] + p[2], p[1] + p[3])
    }

    private fun parseResize(value: String): Pair<Int, Int>? {
        val p = value.split("-").mapNotNull { it.toIntOrNull() }
        return when (p.size) {
            1 -> p[0] to p[0]
            2 -> p[0] to p[1]
            else -> null
        }
    }

    private val YT_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val VIMEO_ID = Regex("^\\d{2,10}$")

    /** Macht aus einer Ressource (YouTube-ID, Vimeo-ID, URL) eine URL. */
    fun toUrl(resource: String): String? = when {
        "://" in resource -> resource
        "/" in resource -> "https://$resource"
        VIMEO_ID.matches(resource) -> "https://vimeo.com/$resource"
        YT_ID.matches(resource) -> "https://www.youtube.com/watch?v=$resource"
        else -> null
    }

    fun sanitizeFilename(name: String): String {
        var s = name
        for (c in "?:\"") s = s.replace(c.toString(), "")
        s = s.replace("<", "(").replace(">", ")")
        for (c in "/\\|*") s = s.replace(c, '-')
        return s.trim().trimEnd('.', ' ')
    }
}
