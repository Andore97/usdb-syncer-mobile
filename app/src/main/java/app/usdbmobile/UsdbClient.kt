package app.usdbmobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Zugriff auf usdb.animux.de. Die Logik (Login, Listen-Regex, Detailseite, Textabruf)
 * ist aus dem usdb_syncer (usdb_scraper.py) übernommen.
 */
class UsdbClient(private val prefs: Prefs) {

    companion object {
        const val BASE = "https://usdb.animux.de/"
        const val REGISTER_URL = BASE + "index.php?link=register"

        private const val LOGIN_INVALID = "Login or Password invalid, please try again."
        private const val NOT_LOGGED_IN = "You are not logged in. Login to use this function."
        private const val DATASET_NOT_FOUND = "Datensatz nicht gefunden"

        private val WELCOME = Regex(
            "<td class='row3' colspan='2'>\\s*<span class='gen'>([^<]+) <b>([^<]+)</b>"
        )

        // Gruppen: 1 id, 2 lastchange, 3 sample, 4 cover, 5 artist, 6 title, 7 genre,
        // 8 year, 9 edition, 10 golden, 11 language, 12 creator, 13 rating, 14 views
        private val ROW = Regex(
            "(?d)<tr class=\"list_tr\\d\"\\s+data-songid=\"(\\d+)\"\\s+data-lastchange=\"(\\d+)\"[^>]*?>\\s*" +
                "<td(?:.*?<source src=\"(.*?)\".*?)?></td>" +
                "<td[^>]*?><img src=\"(.*?)\".*?></td>" +
                "<td[^>]*?>(.*?)</td>\\n" +
                "<td[^>]*?><a href=.*?>(.*?)</td>\\n" +
                "<td[^>]*?>(.*?)</td>\\n" +
                "<td[^>]*?>(.*?)</td>\\n" +
                "<td[^>]*?>(.*?)</td>\\n" +
                "<td[^>]*?>(.*?)</td>\\n" +
                "<td[^>]*?>(.*?)</td>\\n" +
                "<td[^>]*?>(.*?)</td>\\n" +
                "<td[^>]*?>(.*?)</td>\\n" +
                "<td[^>]*?>(.*?)</td>"
        )

        private val TAGS = Regex("<[^>]+>")

        private val SUPPORTED_VIDEO = Regex(
            "\\b((?:https?://)?(?:www\\.)?(?:youtube\\.com|youtube-nocookie\\.com|youtu\\.be|vimeo\\.com|" +
                "player\\.vimeo\\.com|dailymotion\\.com|soundcloud\\.com|facebook\\.com|fb\\.watch|archive\\.org|" +
                "web\\.archive\\.org|v\\.animethemes\\.moe|(?:[A-Za-z0-9-]+\\.)?bandcamp\\.com|bilibili\\.com|" +
                "cda\\.pl|drive\\.google\\.com|nicovideo\\.jp|ok\\.ru|rumble\\.com|universal-music\\.de)/\\S+)"
        )

        private val YT_URL = Regex(
            "(?:https?://)?(?:www\\.)?(?:m\\.)?(?:youtube\\.com/|youtube-nocookie\\.com/|youtu\\.be)" +
                "\\S*(?:/|%3D|v=|vi=)([0-9a-z_-]{11})(?:[%#?&]|$).*",
            RegexOption.IGNORE_CASE
        )
    }

    private class MemoryCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                if (c.expiresAt > System.currentTimeMillis()) store.add(c)
            }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store.filter { it.matches(url) }
        @Synchronized fun clear() = store.clear()
    }

    private val cookies = MemoryCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/126.0 Mobile Safari/537.36"
                    ).build()
            )
        }
        .build()

    private val loginLock = Mutex()
    @Volatile private var loggedIn = false

    private class SessionExpired : Exception()

    // ---------- Login ----------

    private fun doLogin(user: String, pass: String): String {
        if (user.isBlank() || pass.isBlank()) throw NeedsLoginException()
        cookies.clear()
        val t = request("index.php", emptyMap(), mapOf("user" to user, "pass" to pass, "login" to "Login"))
        if (LOGIN_INVALID in t) throw LoginFailedException()
        val name = currentUser() ?: throw LoginFailedException()
        loggedIn = true
        return name
    }

    private fun currentUser(): String? {
        val t = request("index.php", mapOf("link" to "profil"), null)
        return WELCOME.find(t)?.groupValues?.get(2)
    }

    /** Für den „Anmelden & testen“-Knopf. Liefert den USDB-Benutzernamen. */
    suspend fun testLogin(user: String, pass: String): String = loginLock.withLock {
        withContext(Dispatchers.IO) { doLogin(user, pass) }
    }

    fun invalidateSession() { loggedIn = false }

    private suspend fun ensureLogin() = loginLock.withLock {
        if (!loggedIn) withContext(Dispatchers.IO) { doLogin(prefs.user.value, prefs.pass.value) }
    }

    // ---------- HTTP ----------

    private fun request(rel: String, params: Map<String, String>, form: Map<String, String>?): String {
        val url = (BASE + rel).toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val b = Request.Builder().url(url)
        if (form != null) {
            b.post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
        }
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("USDB antwortet mit HTTP ${r.code}")
            return String(r.body!!.bytes(), Charsets.UTF_8)
        }
    }

    private fun checked(rel: String, params: Map<String, String>, form: Map<String, String>?, unescape: Boolean): String {
        val raw = request(rel, params, form)
        val text = Normalizer.normalize(Parser.unescapeEntities(raw, false), Normalizer.Form.NFC)
        if (NOT_LOGGED_IN in text) throw SessionExpired()
        if (DATASET_NOT_FOUND in text) throw SongNotFoundException()
        return if (unescape) text else raw
    }

    private suspend fun page(
        rel: String,
        params: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
        unescape: Boolean = true,
    ): String {
        ensureLogin()
        return withContext(Dispatchers.IO) {
            try {
                checked(rel, params, form, unescape)
            } catch (e: SessionExpired) {
                loggedIn = false
                ensureLogin()
                try {
                    checked(rel, params, form, unescape)
                } catch (e2: SessionExpired) {
                    throw LoginFailedException()
                }
            }
        }
    }

    private fun abs(u: String) = if (u.startsWith("http")) u else BASE + u.removePrefix("/")

    // ---------- Suche ----------

    /**
     * Suche nach Interpret ODER Titel. Mit " - " im Suchtext („Interpret - Titel“) werden
     * beide Felder gleichzeitig gefiltert. Leerer Suchtext = alle Songs (nach [sort]).
     * [language] = englischer USDB-Sprachname (z. B. "German") oder null.
     */
    suspend fun search(query: String, start: Int, sort: Sort, language: String?, limit: Int = 50): List<Song> {
        val q = query.trim()
        val lang = if (language != null) mapOf("language" to language) else emptyMap()
        if (q.isEmpty()) return list(lang, start, limit, sort)
        if (" - " in q) {
            val (a, t) = q.split(" - ", limit = 2)
            return list(mapOf("interpret" to a.trim(), "title" to t.trim()) + lang, start, limit, sort)
        }
        val byArtist = list(mapOf("interpret" to q) + lang, start, limit, sort)
        val byTitle = list(mapOf("title" to q) + lang, start, limit, sort)
        return (byArtist + byTitle).distinctBy { it.id }
    }

    private suspend fun list(filter: Map<String, String>, start: Int, limit: Int, sort: Sort): List<Song> {
        val form = mapOf(
            "order" to sort.order, "ud" to if (sort.desc) "desc" else "asc",
            "limit" to "$limit", "details" to "1", "start" to "$start",
        ) + filter
        val html = page("index.php", mapOf("link" to "list"), form)
        return ROW.findAll(html).map { m ->
            val g = m.groupValues
            val year = g[8].trim()
            Song(
                id = g[1].toInt(),
                artist = clean(g[5]),
                title = clean(g[6]),
                genre = clean(g[7]),
                year = if (year.length == 4 && year.all { it.isDigit() }) year.toInt() else null,
                edition = clean(g[9]),
                language = clean(g[11]),
                golden = clean(g[10]) in setOf("Yes", "Ja", "Oui"),
                rating = Regex("/star\\.png").findAll(g[13]).count() +
                    0.5f * Regex("/half_star\\.png").findAll(g[13]).count(),
                views = g[14].trim().toIntOrNull() ?: 0,
                coverUrl = g[4].takeIf { it.isNotBlank() && "nocover" !in it }?.let { abs(it) },
                usdbMtime = g[2].toLongOrNull() ?: 0,
            )
        }.toList()
    }

    private fun clean(s: String) = TAGS.replace(s, "").trim()

    // ---------- Song ----------

    suspend fun details(id: Int): SongDetails {
        val html = page("index.php", mapOf("id" to "$id", "link" to "detail"))
        val tables = Jsoup.parse(html).select("table[border=0][width=500]")
        val cover = tables.firstOrNull()?.selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() && "nocover" !in it }?.let { abs(it) }

        val found = LinkedHashSet<String>()
        fun add(u: String) {
            if (!SUPPORTED_VIDEO.matches(u) && !SUPPORTED_VIDEO.containsMatchIn(u)) return
            val yt = YT_URL.matchEntire(u)?.groupValues?.get(1)
            found += if (yt != null) "https://www.youtube.com/watch?v=$yt"
            else if (u.startsWith("http")) u else "https://$u"
        }
        tables.getOrNull(1)?.let { c ->
            c.select("embed[src], iframe[src]").forEach { add(it.attr("src")) }
            c.select("a[href]").forEach { add(it.attr("href")) }
            SUPPORTED_VIDEO.findAll(c.text()).forEach { add(it.groupValues[1]) }
        }
        return SongDetails(cover, found.toList())
    }

    /** Holt den Songtext (.txt) – nur mit Login möglich. */
    suspend fun fetchTxt(id: Int): String {
        val raw = page(
            "index.php", mapOf("link" to "gettxt", "id" to "$id"),
            mapOf("wd" to "1"), unescape = false,
        )
        val m = Regex("<textarea[^>]*>(.*?)</textarea>", RegexOption.DOT_MATCHES_ALL).find(raw)
            ?: throw IOException("Songtext auf der Seite nicht gefunden.")
        return Parser.unescapeEntities(m.groupValues[1], false)
    }

    suspend fun downloadBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Accept", "image/jpeg,image/png,image/webp,image/apng,image/*,*/*;q=0.8")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body!!.bytes()
        }
    }
}
