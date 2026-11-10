package app.usdbmobile

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            ctx,
            "secure_prefs",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("plain_prefs", Context.MODE_PRIVATE)
    }

    /** Ein einzelner, beobachtbarer Einstellungswert (Boolean, Int oder String). */
    inner class Pref<T : Any>(private val key: String, default: T) {
        val flow = MutableStateFlow(read(default))
        var value: T
            get() = flow.value
            set(v) { write(v); flow.value = v }

        @Suppress("UNCHECKED_CAST")
        private fun read(def: T): T = when (def) {
            is Boolean -> sp.getBoolean(key, def) as T
            is Int -> sp.getInt(key, def) as T
            is String -> (sp.getString(key, def) ?: def) as T
            else -> def
        }

        private fun write(v: T) {
            sp.edit().apply {
                when (v) {
                    is Boolean -> putBoolean(key, v)
                    is Int -> putInt(key, v)
                    is String -> putString(key, v)
                }
            }.apply()
        }
    }

    // Ziel & Konto
    val tree = Pref<String>("tree", "")
    val user = Pref("user", "")
    val pass = Pref("pass", "")
    val zip = Pref("zip", false)

    // Standardwerte = Einstellungen aus den Screenshots
    val audio = Pref("audio", true)
    val audioFormat = Pref("audio_format", AudioFormat.M4A.name)
    val audioKbps = Pref("audio_kbps", 256)

    val video = Pref("video", true)
    val videoContainer = Pref("video_container", VideoContainer.MP4_AVC.name)
    val videoHeight = Pref("video_height", 720)
    val videoFps = Pref("video_fps", 30)

    val cover = Pref("cover", true)
    val coverMax = Pref("cover_max", 1920)

    val background = Pref("background", true)
    val backgroundEven = Pref("background_even", true)
    val backgroundMax = Pref("background_max", 1920)

    // Such-Filter (werden gemerkt)
    val sort = Pref("sort", "DATE_DESC")
    val language = Pref("language", "ALL")

    fun hasCredentials() = user.value.isNotBlank() && pass.value.isNotBlank()
    fun hasTree() = tree.value.isNotBlank()

    fun snapshot() = Options(
        zip = zip.value,
        audio = audio.value,
        audioFormat = runCatching { enumValueOf<AudioFormat>(audioFormat.value) }.getOrDefault(AudioFormat.M4A),
        audioKbps = audioKbps.value,
        video = video.value,
        videoContainer = runCatching { enumValueOf<VideoContainer>(videoContainer.value) }
            .getOrDefault(VideoContainer.MP4_AVC),
        maxHeight = videoHeight.value,
        maxFps = videoFps.value,
        cover = cover.value,
        coverMax = coverMax.value,
        background = background.value,
        backgroundEvenWithVideo = backgroundEven.value,
        backgroundMax = backgroundMax.value,
    )
}
