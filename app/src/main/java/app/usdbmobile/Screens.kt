package app.usdbmobile

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.UnknownHostException
import java.util.Locale

// ───────────────────────── Hilfen ─────────────────────────

@Composable
fun <T : Any> Prefs.Pref<T>.collect(): State<T> = flow.collectAsStateWithLifecycle()

@Composable
fun rememberFolderPicker(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Graph.prefs.tree.value = uri.toString()
        }
    }
    return { launcher.launch(null) }
}

private fun errorText(e: Exception) = when (e) {
    is UnknownHostException -> "Keine Internetverbindung."
    else -> e.message ?: "Unbekannter Fehler"
}

// ───────────────────────── Sprach-Filter ─────────────────────────

/** Filter-Schlüssel: ALL, SYSTEM, EN, OTHER */
private object Lang {
    private val sys: Locale get() = Locale.getDefault()
    val sysIsEnglish get() = sys.language == "en"
    val sysDisplay: String get() = sys.getDisplayLanguage(sys).replaceFirstChar { it.uppercase() }
    /** USDB nennt Sprachen auf Englisch, z. B. "German". */
    val sysUsdb: String get() = sys.getDisplayLanguage(Locale.ENGLISH)
    val enDisplay: String get() = Locale.ENGLISH.getDisplayLanguage(sys).replaceFirstChar { it.uppercase() }

    fun options(): List<Pair<String, String>> = buildList {
        add("ALL" to "Alle Sprachen")
        if (!sysIsEnglish) add("SYSTEM" to sysDisplay)
        add("EN" to enDisplay)
        add("OTHER" to "Andere Sprachen")
    }

    fun label(key: String) = options().firstOrNull { it.first == key }?.second ?: "Alle Sprachen"

    /** Parameter für den USDB-Server (null = kein Filter). */
    fun server(key: String): String? = when (key) {
        "SYSTEM" -> sysUsdb
        "EN" -> "English"
        else -> null
    }

    fun matches(key: String, language: String): Boolean = when (key) {
        "SYSTEM" -> language.equals(sysUsdb, true)
        "EN" -> language.equals("English", true)
        "OTHER" -> !language.equals("English", true) && !language.equals(sysUsdb, true)
        else -> true
    }
}

private fun localComparator(sort: Sort): Comparator<Song> = when (sort) {
    Sort.DATE_DESC -> compareByDescending<Song> { it.id }
    Sort.DATE_ASC -> compareBy<Song> { it.id }
    Sort.ARTIST_ASC -> compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
    Sort.ARTIST_DESC -> compareByDescending<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
    Sort.TITLE_ASC -> compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
    Sort.TITLE_DESC -> compareByDescending<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
}

private fun sortOf(key: String) = runCatching { enumValueOf<Sort>(key) }.getOrDefault(Sort.DATE_DESC)

// ───────────────────────── Suche ─────────────────────────

class SearchViewModel : ViewModel() {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Song>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var hasMore by mutableStateOf(false)
    var onlyDownloaded by mutableStateOf(false)
    private var offset = 0
    private var job: Job? = null

    fun search() = load(true)
    fun more() = load(false)

    private fun load(reset: Boolean) {
        job?.cancel()
        if (onlyDownloaded) { loading = false; error = null; hasMore = false; return }
        job = viewModelScope.launch {
            loading = true
            error = null
            try {
                if (reset) offset = 0
                val sort = sortOf(Graph.prefs.sort.value)
                val lang = Graph.prefs.language.value
                val collected = mutableListOf<Song>()
                var tries = 0
                var end = false
                // Sprachfilter wird zusätzlich lokal geprüft; ggf. weitere Seiten nachladen
                do {
                    val page = Graph.usdb.search(query, offset, sort, Lang.server(lang))
                    offset += 50
                    tries++
                    if (page.isEmpty()) { end = true; break }
                    collected += page.filter { Lang.matches(lang, it.language) }
                } while (lang != "ALL" && collected.size < 25 && tries < 6)
                results = if (reset) collected else (results + collected).distinctBy { it.id }
                hasMore = !end
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun SearchScreen(snack: SnackbarHostState, goSettings: () -> Unit, vm: SearchViewModel = viewModel()) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val pickFolder = rememberFolderPicker()
    val prefs = Graph.prefs

    val tree by prefs.tree.collect()
    val user by prefs.user.collect()
    val pass by prefs.pass.collect()
    val sortKey by prefs.sort.collect()
    val langKey by prefs.language.collect()
    val all by Graph.downloads.items.collectAsStateWithLifecycle()
    val lib by Graph.library.entries.collectAsStateWithLifecycle()
    val scanning by Graph.library.scanning.collectAsStateWithLifecycle()
    val statusById = all.associate { it.song.id to it.status }
    val hasCreds = user.isNotBlank() && pass.isNotBlank()
    val sort = sortOf(sortKey)

    // Bei Änderung von Filter/Sortierung (und beim ersten Einrichten) neu suchen
    LaunchedEffect(hasCreds, sortKey, langKey, vm.onlyDownloaded) {
        if (hasCreds && !vm.onlyDownloaded) vm.search()
    }

    // „Nur geladene“: lokal aus der Bibliothek, ohne Netz
    val shown: List<Song> = if (vm.onlyDownloaded) {
        val q = vm.query.trim().lowercase()
        lib.values.map { it.song }
            .filter { q.isEmpty() || "${it.artist} ${it.title}".lowercase().contains(q) }
            .filter { Lang.matches(langKey, it.language) }
            .sortedWith(localComparator(sort))
    } else vm.results

    fun download(song: Song) {
        scope.launch {
            when {
                !hasCreds -> {
                    if (snack.showSnackbar("Zuerst den USDB-Login eintragen", "Einstellungen") ==
                        SnackbarResult.ActionPerformed
                    ) goSettings()
                }
                tree.isBlank() -> {
                    if (snack.showSnackbar("Zuerst einen Zielordner wählen", "Wählen") ==
                        SnackbarResult.ActionPerformed
                    ) pickFolder()
                }
                else -> {
                    Graph.downloads.enqueue(song)
                    snack.currentSnackbarData?.dismiss()
                    snack.showSnackbar("„${song.title}“ wird geladen – Fortschritt unter „Downloads“")
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            placeholder = { Text(if (vm.onlyDownloaded) "In geladenen Songs suchen" else "Interpret oder Titel suchen") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton(onClick = { vm.query = ""; vm.search() }) { Icon(Icons.Default.Close, "Löschen") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }),
        )

        // Filterleiste
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MenuChip(
                label = sort.label, selected = sort != Sort.DATE_DESC,
                options = Sort.entries.map { it.name to it.label },
            ) { prefs.sort.value = it }
            MenuChip(
                label = if (langKey == "ALL") "Sprache" else Lang.label(langKey), selected = langKey != "ALL",
                options = Lang.options(),
            ) { prefs.language.value = it }
            FilterChip(
                selected = vm.onlyDownloaded,
                onClick = { vm.onlyDownloaded = !vm.onlyDownloaded },
                label = { Text("Geladene Songs") },
                leadingIcon = { Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp)) },
            )
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!hasCreds || tree.isBlank()) {
                item { SetupCard(hasCreds, tree.isNotBlank(), goSettings, pickFolder) }
            }
            vm.error?.let { msg ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(msg, color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = { vm.search() }) { Text("Nochmal versuchen") }
                        }
                    }
                }
            }
            items(shown, key = { it.id }) { song ->
                SongCard(song, statusById[song.id], lib[song.id]) { download(song) }
            }
            item {
                when {
                    vm.loading && !vm.onlyDownloaded ->
                        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator() }
                    shown.isEmpty() && vm.error == null && (hasCreds || vm.onlyDownloaded) ->
                        Text(
                            if (vm.onlyDownloaded) "Noch keine geladenen Songs gefunden." else "Keine Treffer.",
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge,
                        )
                    vm.hasMore && !vm.onlyDownloaded ->
                        OutlinedButton(onClick = { vm.more() }, Modifier.fillMaxWidth()) { Text("Mehr laden") }
                }
            }
        }
    }
}

/** Chip, der beim Antippen ein Auswahlmenü öffnet. */
@Composable
private fun MenuChip(label: String, selected: Boolean, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected, onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(key) })
            }
        }
    }
}

@Composable
private fun SetupCard(hasCreds: Boolean, hasTree: Boolean, goSettings: () -> Unit, pickFolder: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Einmalige Einrichtung", style = MaterialTheme.typography.titleMedium)
            SetupRow("USDB-Konto", hasCreds, "Eintragen", goSettings)
            SetupRow("Zielordner für die Songs", hasTree, "Wählen", pickFolder)
        }
    }
}

@Composable
private fun SetupRow(label: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Default.Check else Icons.Default.Close, null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f))
        if (!done) Button(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun SongCard(song: Song, status: Status?, lib: LibEntry?, onDownload: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (song.coverUrl != null) {
                AsyncImage(
                    model = song.coverUrl, contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    Alignment.Center,
                ) { Icon(Icons.Default.MusicNote, null) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.artist, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    song.year?.toString(), song.language.ifBlank { null },
                    if (song.rating > 0) "★ ${song.rating}" else null,
                    if (song.golden) "Golden Notes" else null,
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(92.dp), Alignment.Center) {
                when {
                    status is Status.Queued -> Text("Wartet …", style = MaterialTheme.typography.labelLarge)
                    status is Status.Working -> CircularProgressIndicator(progress = { status.progress }, modifier = Modifier.size(36.dp))
                    status is Status.Done || (lib != null && status !is Status.Failed) -> {
                        // Schon geladen: Symbol statt Download-Button, „Neu laden“ im Menü
                        Column(
                            Modifier.clip(RoundedCornerShape(12.dp)).clickable { menu = true }.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Default.CheckCircle, "Geladen", tint = MaterialTheme.colorScheme.primary)
                            Text("Geladen", style = MaterialTheme.typography.labelMedium)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Neu laden") },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                onClick = { menu = false; onDownload() },
                            )
                        }
                    }
                    else -> FilledTonalButton(onClick = onDownload, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (status is Status.Failed) "Nochmal" else "Laden")
                    }
                }
            }
        }
    }
}

// ───────────────────────── Downloads ─────────────────────────

@Composable
fun DownloadsScreen() {
    val list by Graph.downloads.items.collectAsStateWithLifecycle()
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
            Text(
                "Noch keine Downloads.\nIn der Suche auf „Laden“ tippen.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list.reversed(), key = { it.song.id }) { DownloadRow(it) }
        }
        if (list.any { !it.status.isActive }) {
            TextButton(onClick = { Graph.downloads.clearFinished() }, Modifier.fillMaxWidth().padding(8.dp)) {
                Text("Fertige entfernen")
            }
        }
    }
}

@Composable
private fun DownloadRow(item: Item) {
    val s = item.status
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${item.song.artist} – ${item.song.title}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                when (s) {
                    Status.Queued -> Text("Wartet …", style = MaterialTheme.typography.bodyMedium)
                    is Status.Working -> {
                        Text(s.step, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    is Status.Done -> Text("Gespeichert: ${s.where}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    is Status.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Status.Cancelled -> Text("Abgebrochen", style = MaterialTheme.typography.bodyMedium)
                }
            }
            when (s) {
                Status.Queued, is Status.Working ->
                    IconButton(onClick = { Graph.downloads.cancel(item.song.id) }) { Icon(Icons.Default.Close, "Abbrechen") }
                is Status.Failed, Status.Cancelled ->
                    IconButton(onClick = { Graph.downloads.enqueue(item.song) }) { Icon(Icons.Default.Refresh, "Nochmal") }
                is Status.Done ->
                    IconButton(onClick = { Graph.downloads.remove(item.song.id) }) { Icon(Icons.Default.Close, "Entfernen") }
            }
        }
    }
}

// ───────────────────────── Einstellungen ─────────────────────────

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val pickFolder = rememberFolderPicker()
    val p = Graph.prefs

    val tree by p.tree.collect()
    val zip by p.zip.collect()
    val audioOn by p.audio.collect()
    val audioFmt by p.audioFormat.collect()
    val audioKbps by p.audioKbps.collect()
    val videoOn by p.video.collect()
    val videoCont by p.videoContainer.collect()
    val videoH by p.videoHeight.collect()
    val videoFps by p.videoFps.collect()
    val coverOn by p.cover.collect()
    val coverMax by p.coverMax.collect()
    val bgOn by p.background.collect()
    val bgEven by p.backgroundEven.collect()
    val bgMax by p.backgroundMax.collect()
    val yt by Graph.ytStatus.collectAsStateWithLifecycle()
    val scanning by Graph.library.scanning.collectAsStateWithLifecycle()
    val libCount by Graph.library.entries.collectAsStateWithLifecycle()

    var user by remember { mutableStateOf(p.user.value) }
    var pass by remember { mutableStateOf(p.pass.value) }
    var loginMsg by remember { mutableStateOf<String?>(null) }
    var loginOk by remember { mutableStateOf(false) }
    var loginBusy by remember { mutableStateOf(false) }
    var ytMsg by remember { mutableStateOf<String?>(null) }
    var ytBusy by remember { mutableStateOf(false) }

    val folderName = remember(tree) {
        if (tree.isBlank()) null else DocumentFile.fromTreeUri(ctx, tree.toUri())?.name ?: "Ordner gewählt"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsCard("Zielordner & Format") {
            Text(folderName ?: "Noch nicht gewählt", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Hier landet pro Song ein Ordner (Text, Audio, Video, Cover, Hintergrund und eine .usdb-Datei) " +
                    "oder – mit „Als ZIP packen“ – eine ZIP-Datei.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = pickFolder) { Text(if (tree.isBlank()) "Ordner wählen" else "Ordner ändern") }
                OutlinedButton(
                    enabled = tree.isNotBlank() && !scanning,
                    onClick = { scope.launch { Graph.library.scan() } },
                ) { Text(if (scanning) "Lese ein …" else "Neu einlesen") }
            }
            Text(
                "${libCount.size} geladene Songs erkannt",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow("Als ZIP packen", "Statt eines Ordners eine ZIP-Datei pro Song", zip) { p.zip.value = it }
        }

        SettingsCard("USDB-Konto") {
            OutlinedTextField(
                value = user, onValueChange = { user = it; loginMsg = null },
                label = { Text("Benutzername") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pass, onValueChange = { pass = it; loginMsg = null },
                label = { Text("Passwort") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(
                enabled = !loginBusy && user.isNotBlank() && pass.isNotBlank(),
                onClick = {
                    loginBusy = true
                    scope.launch {
                        try {
                            val name = Graph.usdb.testLogin(user.trim(), pass)
                            p.user.value = user.trim(); p.pass.value = pass
                            loginOk = true
                            loginMsg = "Angemeldet als $name"
                        } catch (e: Exception) {
                            loginOk = false
                            loginMsg = errorText(e)
                        } finally {
                            loginBusy = false
                        }
                    }
                },
            ) { Text(if (loginBusy) "Prüfe …" else "Anmelden & testen") }
            loginMsg?.let {
                Text(it, color = if (loginOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = { uriHandler.openUri(UsdbClient.REGISTER_URL) }) {
                Text("Noch kein Konto? Auf USDB registrieren")
            }
        }

        SettingsCard("Audio", checked = audioOn, onChecked = { p.audio.value = it }, checkLabel = "Audio laden") {
            val fmts = AudioFormat.entries
            DropdownField(
                "Format:", fmts.firstOrNull { it.name == audioFmt }?.label ?: AudioFormat.M4A.label,
                fmts.map { it.label }, audioOn,
            ) { p.audioFormat.value = fmts[it].name }
            DropdownField(
                "Bitrate:", "$audioKbps kbps", Choices.audioBitrates.map { "$it kbps" }, audioOn,
            ) { p.audioKbps.value = Choices.audioBitrates[it] }
        }

        SettingsCard("Video", checked = videoOn, onChecked = { p.video.value = it }, checkLabel = "Video laden") {
            val conts = VideoContainer.entries
            DropdownField(
                "Container/Codec:", conts.firstOrNull { it.name == videoCont }?.label ?: VideoContainer.MP4_AVC.label,
                conts.map { it.label }, videoOn,
            ) { p.videoContainer.value = conts[it].name }
            DropdownField(
                "Max. Auflösung:", "${videoH}p", Choices.resolutions.map { "${it.first}p" }, videoOn,
            ) { p.videoHeight.value = Choices.resolutions[it].first }
            DropdownField(
                "Max. FPS:", "$videoFps", Choices.fps.map { "$it" }, videoOn,
            ) { p.videoFps.value = Choices.fps[it] }
        }

        SettingsCard("Cover", checked = coverOn, onChecked = { p.cover.value = it }, checkLabel = "Cover laden") {
            DropdownField(
                "Max. Abmessungen:", Choices.sizeLabel(coverMax), Choices.coverMax.map { Choices.sizeLabel(it) }, coverOn,
            ) { p.coverMax.value = Choices.coverMax[it] }
        }

        SettingsCard("Hintergrund", checked = bgOn, onChecked = { p.background.value = it }, checkLabel = "Hintergrund laden") {
            SwitchRow("Auch mit Video", "Hintergrund laden, obwohl ein Video vorhanden ist", bgEven, bgOn) { p.backgroundEven.value = it }
            DropdownField(
                "Max. Abmessungen:", Choices.sizeLabel(bgMax), Choices.backgroundMax.map { Choices.sizeLabel(it) }, bgOn,
            ) { p.backgroundMax.value = Choices.backgroundMax[it] }
        }

        SettingsCard("Downloader (yt-dlp)") {
            Text(
                when {
                    yt == "ready" -> "Bereit"
                    yt == "loading" -> "Wird gestartet …"
                    else -> "Fehler: ${yt.removePrefix("error: ")}"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Wenn Downloads plötzlich fehlschlagen, hilft meist ein Update von yt-dlp.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                enabled = yt == "ready" && !ytBusy,
                onClick = {
                    ytBusy = true
                    ytMsg = null
                    scope.launch {
                        ytMsg = try {
                            withContext(Dispatchers.IO) { YoutubeDL.getInstance().updateYoutubeDL(Graph.app) }
                            "yt-dlp ist aktuell."
                        } catch (e: Exception) {
                            "Update fehlgeschlagen: ${errorText(e)}"
                        } finally {
                            ytBusy = false
                        }
                    }
                },
            ) { Text(if (ytBusy) "Aktualisiere …" else "yt-dlp aktualisieren") }
            ytMsg?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }

        Text(
            "Basiert auf dem Ablauf des USDB Syncer (GPL-3.0). Songs und Videos gehören ihren Urhebern – " +
                "bitte nur für den privaten Gebrauch laden.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Karte mit Titel; mit [checked] erscheint (wie im Syncer) ein Kontrollkästchen in der Kopfzeile. */
@Composable
private fun SettingsCard(
    title: String,
    checked: Boolean? = null,
    onChecked: (Boolean) -> Unit = {},
    checkLabel: String = title,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (checked == null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            } else {
                Row(
                    Modifier.fillMaxWidth().clickable { onChecked(!checked) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = onChecked)
                    Text(checkLabel, style = MaterialTheme.typography.titleMedium)
                }
            }
            content()
        }
    }
}

@Composable
private fun DropdownField(label: String, value: String, options: List<String>, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled) {
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, t ->
                    DropdownMenuItem(text = { Text(t) }, onClick = { open = false; onSelect(i) })
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
