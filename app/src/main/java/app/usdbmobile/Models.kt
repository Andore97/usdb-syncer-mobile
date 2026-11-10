package app.usdbmobile

data class Song(
    val id: Int,
    val artist: String,
    val title: String,
    val genre: String,
    val year: Int?,
    val edition: String,
    val language: String,
    val golden: Boolean,
    val rating: Float,
    val views: Int,
    val coverUrl: String?,
    /** data-lastchange aus der USDB-Liste (für die .usdb-Datei). */
    val usdbMtime: Long = 0,
)

class SongDetails(
    val coverUrl: String?,
    /** Video-Links aus den Kommentaren (neueste zuerst), als Fallback. */
    val commentVideos: List<String>,
)

class NeedsLoginException : Exception("USDB-Login fehlt. Bitte unter „Einstellungen“ eintragen.")
class LoginFailedException : Exception("Login bei USDB fehlgeschlagen. Benutzername/Passwort prüfen.")
class SongNotFoundException : Exception("Song auf USDB nicht gefunden.")
class UserCancelled : Exception("Abgebrochen")
