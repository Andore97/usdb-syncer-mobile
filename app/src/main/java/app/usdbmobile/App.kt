package app.usdbmobile

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Einfacher Service-Locator, damit UI, Service und Downloads dieselben Objekte nutzen. */
object Graph {
    lateinit var app: Application
    lateinit var prefs: Prefs
    lateinit var usdb: UsdbClient
    lateinit var downloads: DownloadManager
    lateinit var library: Library

    /** "loading", "ready" oder "error: ..." */
    val ytStatus = MutableStateFlow("loading")

    fun init(application: Application) {
        app = application
        prefs = Prefs(application)
        usdb = UsdbClient(prefs)
        downloads = DownloadManager(application)
        library = Library(application)
        // Bibliothek beim Start und bei jedem Wechsel des Zielordners neu einlesen
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            prefs.tree.flow.collect { library.scan() }
        }
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                Graph.ytStatus.value = "ready"
            } catch (e: Exception) {
                Graph.ytStatus.value = "error: ${e.message}"
            }
        }.start()
    }
}
