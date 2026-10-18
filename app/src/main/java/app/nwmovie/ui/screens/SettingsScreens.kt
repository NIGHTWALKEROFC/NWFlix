package app.nwmovie.ui.screens

import androidx.biometric.BiometricManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nwmovie.BuildConfig
import app.nwmovie.MainViewModel
import app.nwmovie.data.CacheManager
import app.nwmovie.data.K
import app.nwmovie.data.MovieGroup
import app.nwmovie.data.Integrity
import app.nwmovie.data.Updater
import app.nwmovie.data.db.DownloadItem
import app.nwmovie.data.Secrets
import app.nwmovie.ui.components.StorageBar
import app.nwmovie.ui.theme.Accents
import app.nwmovie.util.fmtSize
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- main list

@Composable
fun SettingsScreen(vm: MainViewModel, onPage: (String) -> Unit) {
    val user by vm.userName.collectAsState()
    val rows = listOf(
        Triple(Icons.Default.Person, "Account & Security", "account"),
        Triple(Icons.Default.Lock, "Privacy & Notifications", "privacy"),
        Triple(Icons.Default.Palette, "Appearance", "appearance"),
        Triple(Icons.Default.PlayCircle, "Player Settings", "player"),
        Triple(Icons.Default.Wifi, "Network & Download", "network"),
        Triple(Icons.Default.Storage, "Storage & Cache", "storage"),
        Triple(Icons.Default.BarChart, "Watch statistics", "stats"),
        Triple(Icons.Default.SystemUpdate, "App updates", "updates"),
        Triple(Icons.Default.HelpOutline, "Help & Support", "help")
    )
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp, 8.dp))
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(user.firstOrNull()?.uppercase() ?: "N", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(user.ifBlank { "NWMovie user" }, style = MaterialTheme.typography.titleMedium)
                Text("Connected with Telegram", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        rows.forEach { (icon, title, page) ->
            Row(Modifier.fillMaxWidth().clickable { onPage(page) }.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------- building blocks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
private fun Group(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, sub: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, value: T, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(options.firstOrNull { it.first == value }?.second ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, confirmButton = {},
        dismissButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (v, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(v); open = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = v == value, onClick = { onPick(v); open = false })
                        Text(label)
                    }
                }
            }
        })
}

@Composable
private fun SliderRow(title: String, label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onDone: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row { Text(title, Modifier.weight(1f)); Text(label.ifEmpty { v.toInt().toString() }, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Slider(v, { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { onDone(v) })
    }
}

@Composable
private fun ActionRow(title: String, sub: String? = null, icon: ImageVector? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Confirm(title: String, text: String, ok: String, onOk: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onOk(); onClose() }) { Text(ok) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

private val langs = listOf("" to "Auto / none", "eng" to "English", "hin" to "Hindi", "tam" to "Tamil", "mal" to "Malayalam", "tel" to "Telugu",
    "kan" to "Kannada", "spa" to "Spanish", "fre" to "French", "ger" to "German", "jpn" to "Japanese", "kor" to "Korean", "ara" to "Arabic")

// ---------------------------------------------------------------- pages

@Composable
fun SettingsPages(vm: MainViewModel, name: String, onBack: () -> Unit, onPage: (String) -> Unit = {}) {
    val p by vm.prefs.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    when (name) {
        "account" -> Page("Account & Security", onBack) {
            val user by vm.userName.collectAsState()
            var logout by remember { mutableStateOf(false) }
            Group("Telegram")
            ActionRow(user.ifBlank { "Telegram user" }, "Connected. Your session is encrypted on this phone.", Icons.Default.Person) {}
            ActionRow("Scan for new movies", "Also runs automatically every time you open the app", Icons.Default.Refresh) { vm.rescan(false); vm.say("Scanning…") }
            ActionRow("Full rescan", "Slower: checks every chat deeply and retries unmatched movies", Icons.Default.Search) { vm.rescan(true); vm.say("Full scan started") }
            Group("Danger zone")
            ActionRow("Log out & clear session", "Signs out of Telegram and deletes everything stored by NWMovie", Icons.Default.Logout, danger = true) { logout = true }
            if (logout) Confirm("Log out?", "This logs you out of Telegram on this phone and deletes the local index, caches and downloaded movies.", "Log out",
                { vm.logout() }, { logout = false })
        }

        "privacy" -> Page("Privacy & Security", onBack) {
            var clearSession by remember { mutableStateOf(false) }
            var clearData by remember { mutableStateOf(false) }
            Group("App lock")
            SwitchRow("Biometric / PIN lock", "Fingerprint, face or your phone's PIN is needed to open NWMovie", p.appLock) { on ->
                if (on) {
                    val ok = BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
                        BiometricManager.BIOMETRIC_SUCCESS
                    if (ok) vm.set(K.appLock, true) else vm.say("Set a screen lock (PIN, pattern or fingerprint) in your phone settings first.")
                } else vm.set(K.appLock, false)
            }
            SwitchRow("Hide notification previews", "Notifications won't show movie titles", p.hideNotif) { vm.set(K.hideNotif, it) }
            SwitchRow("Block screenshots & hide in recent apps", null, p.blockShots) { vm.set(K.blockShots, it) }
            Group("Your data")
            ActionRow("Secure Telegram session", "Stored in an encrypted database; the key is kept in the Android Keystore. Logs never contain your data.", Icons.Default.Shield) {}
            ActionRow("Clear session", "Log out of Telegram and remove all local data", Icons.Default.Logout, danger = true) { clearSession = true }
            ActionRow("Clear all local data", "Deletes index, caches and downloads (stays logged in)", Icons.Default.Delete, danger = true) { clearData = true }
            Group("About")
            ActionRow("NWMovie ${BuildConfig.VERSION_NAME}", "Terms: use only content you are authorised to access.") {}
            ActionRow("Movie data by TMDB", "This product uses the TMDB API but is not endorsed or certified by TMDB.") {}
            ActionRow("Telegram", "Built on TDLib (Boost Software License). Telegram is a trademark of its owner; NWMovie is not affiliated with Telegram.") {}
            if (clearSession) Confirm("Clear session?", "You will be logged out and all local NWMovie data will be deleted.", "Clear", { vm.logout() }, { clearSession = false })
            if (clearData) Confirm("Delete all local data?", "Index, caches and downloaded movies will be removed.", "Delete", { vm.clearLocalData() }, { clearData = false })
        }

        "stats" -> Page("Watch statistics", onBack) { StatsContent(vm) }

        "space" -> Page("Free up space", onBack) { SpaceContent(vm) }

        "updates" -> Page("App updates", onBack) { UpdatesContent(vm) }

        "appearance" -> Page("Appearance", onBack) {
            Group("Theme")
            ChoiceRow("Theme", p.theme, listOf("dark" to "Midnight (navy)", "amoled" to "AMOLED black", "light" to "Light")) { vm.set(K.theme, it) }
            Group("Accent colour")
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Accents.forEachIndexed { i, c ->
                    Box(Modifier.size(38.dp).clip(CircleShape).background(c).clickable { vm.set(K.accent, i) },
                        contentAlignment = Alignment.Center) { if (p.accent == i) Icon(Icons.Default.Check, null, tint = Color.White) }
                }
            }
            SliderRow("Text size", "${(p.fontScale * 100).toInt()}%", p.fontScale, 0.85f..1.3f, 8) { vm.set(K.fontScale, it) }
            Group("Display")
            SwitchRow("Full-screen player", "Hide system bars while watching", p.fullscreen) { vm.set(K.fullscreen, it) }
            SwitchRow("Show movie titles under posters", null, p.showTitles) { vm.set(K.showTitles, it) }
        }

        "player" -> Page("Player Settings", onBack) {
            Group("Playback")
            ChoiceRow("Default playback speed", p.speed, listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { it to "${it}x" }) { vm.set(K.speed, it) }
            SwitchRow("Resume playback", "Continue where you stopped", p.resume) { vm.set(K.resume, it) }
            SwitchRow("Auto-play next", "After a movie ends, play the next unwatched title in My List", p.autoNext) { vm.set(K.autoNext, it) }
            SwitchRow("Background playback", "Keep playing when you leave the app (off = the movie pauses)", p.bgPlayback) { vm.set(K.bgPlayback, it) }
            Group("Compatibility")
            ChoiceRow("Player engine", p.engine, listOf("auto" to "Automatic (VLC engine only when needed)", "exo" to "Standard player only", "vlc" to "Always VLC engine (all formats)")) { vm.set(K.engine, it) }
            Group("Audio & subtitles")
            ChoiceRow("Sound preset", p.soundPreset, listOf("off" to "Off", "voice" to "Voice boost", "night" to "Night mode")) { vm.set(K.soundPreset, it) }
            ChoiceRow("Preferred audio language", p.audioLang, langs) { vm.set(K.audioLang, it) }
            ChoiceRow("Preferred subtitle language", p.subLang, langs.map { if (it.first == "") "" to "Off" else it }) { vm.set(K.subLang, it) }
            SliderRow("Subtitle size", "${p.subSize.toInt()} sp", p.subSize, 12f..36f, 11) { vm.set(K.subSize, it) }
            ChoiceRow("Subtitle colour", p.subColor, listOf(0xFFFFFFFF.toInt() to "White", 0xFFFFE066.toInt() to "Yellow", 0xFF8FF0FF.toInt() to "Cyan")) { vm.set(K.subColor, it) }
            SwitchRow("Subtitle background", "Dark box behind text for easier reading", p.subBg) { vm.set(K.subBg, it) }
            SliderRow("Default subtitle delay", "${p.subDelayMs} ms", p.subDelayMs.toFloat(), -5000f..5000f, 99) { vm.set(K.subDelayMs, (it / 100).toInt() * 100) }
            SliderRow("Default audio delay", "${p.audioDelayMs} ms", p.audioDelayMs.toFloat(), 0f..2000f, 19) { vm.set(K.audioDelayMs, (it / 100).toInt() * 100) }
            Group("Video")
            ChoiceRow("Aspect ratio", p.aspect, listOf("fit" to "Fit to screen", "fill" to "Stretch", "crop" to "Crop (zoom)", "16:9" to "16:9", "4:3" to "4:3")) { vm.set(K.aspect, it) }
            ChoiceRow("Screen rotation", p.rotation, listOf("auto" to "Auto-rotate", "landscape" to "Landscape", "portrait" to "Portrait", "video" to "Match video")) { vm.set(K.rotation, it) }
            SwitchRow("Hardware acceleration", "Turn off if a movie shows green/black video (uses software decoding)", p.hwAccel) { vm.set(K.hwAccel, it) }
            SwitchRow("Automatic quality", "Adapt quality to your network when a stream offers several", p.autoQuality) { vm.set(K.autoQuality, it) }
            ChoiceRow("Maximum quality", p.maxQuality, listOf(0 to "No limit", 2160 to "2160p", 1080 to "1080p", 720 to "720p", 480 to "480p")) { vm.set(K.maxQuality, it) }
            Group("Gestures")
            SwitchRow("Enable gestures", "Swipe and double-tap on the video", p.gestures) { vm.set(K.gestures, it) }
            SwitchRow("Brightness (swipe left side)", null, p.brightnessGesture, p.gestures) { vm.set(K.brightnessGesture, it) }
            SwitchRow("Volume (swipe right side)", "Goes up to 200 % like VLC. Above 100 % the sound is boosted and may distort.", p.volumeGesture, p.gestures) { vm.set(K.volumeGesture, it) }
            SwitchRow("Seek (swipe sideways)", null, p.seekGesture, p.gestures) { vm.set(K.seekGesture, it) }
            SwitchRow("Remember brightness", "Start the next movie with the brightness you chose", p.rememberBrightness) { vm.set(K.rememberBrightness, it) }
            SwitchRow("Remember volume", "Start the next movie with the volume you chose (up to 200 %)", p.rememberVolume) { vm.set(K.rememberVolume, it) }
            ChoiceRow("Double-tap seek", p.doubleTapSec, listOf(5, 10, 15, 30).map { it to "$it seconds" }) { vm.set(K.doubleTapSec, it) }
        }

        "network" -> Page("Network & Download", onBack) {
            var askSaver by remember { mutableStateOf(false) }
            Group("Data saver")
            SwitchRow("Data saver", "Uses less data but lowers quality: smaller pictures, smaller streaming read-ahead, and the smallest file is played when a movie has several versions.", p.bandwidthSaver) { on ->
                if (on) askSaver = true else vm.set(K.bandwidthSaver, false)
            }
            if (askSaver) Confirm("Turn on Data saver?",
                "This reduces data usage but lowers quality. Pictures get smaller, the smallest file is chosen when a movie has several versions, and a slow connection may buffer more often.",
                "Turn on", { vm.set(K.bandwidthSaver, true) }, { askSaver = false })
            Group("Smart quality")
            SwitchRow("Smart file choice", "When a movie has several files: best quality on Wi-Fi, smallest file on mobile data (without turning on Data saver).", p.smartQuality) { vm.set(K.smartQuality, it) }
            Group("Download schedule")
            SwitchRow("Only while charging", "Downloads wait until the phone is charging", p.dlOnlyCharging) { vm.set(K.dlOnlyCharging, it) }
            SwitchRow("Only at night (11 PM to 7 AM)", "Downloads wait for night time. They continue while the app is open.", p.dlOnlyNight) { vm.set(K.dlOnlyNight, it) }
            Text("Wi-Fi only for downloads is in the next section. All rules apply together.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            Group("Mobile data")
            SwitchRow("Allow mobile data", "Off = NWMovie only uses Wi-Fi for everything", p.mobileData) { vm.set(K.mobileData, it) }
            SwitchRow("Wi-Fi only for streaming", null, p.wifiOnlyStream) { vm.set(K.wifiOnlyStream, it) }
            SwitchRow("Wi-Fi only for downloads", null, p.wifiOnlyDownload) { vm.set(K.wifiOnlyDownload, it) }
            Group("Background")
            SwitchRow("Downloads in background", "Off = downloads pause when you leave the app and continue when you return", p.bgDownloads) { vm.set(K.bgDownloads, it) }
            SwitchRow("Automatic quality", null, p.autoQuality) { vm.set(K.autoQuality, it) }
            ChoiceRow("Maximum streaming quality", p.maxQuality, listOf(0 to "No limit", 1080 to "1080p", 720 to "720p", 480 to "480p")) { vm.set(K.maxQuality, it) }
        }

        "storage" -> Page("Storage & Cache", onBack) {
            var usage by remember { mutableStateOf(CacheManager.usage(ctx)) }
            fun refresh() { usage = CacheManager.usage(ctx) }
            LaunchedEffect(Unit) { refresh() }
            var confirmClear by remember { mutableStateOf<String?>(null) }
            Group("Cache (safe to clear)")
            CacheRow("Posters & images", usage.posters) { CacheManager.clearPosters(); refresh() }
            CacheRow("Movie details (metadata)", usage.metadata) { CacheManager.clearMetadata(); refresh() }
            CacheRow("Movie index", usage.index, "Your lists & history are kept") { scope.launch { CacheManager.clearIndex(); refresh(); vm.rescan(false) } }
            CacheRow("Temporary playback files", usage.temp) { scope.launch { CacheManager.clearTemp(); refresh() } }
            Group("Downloads (kept separate from caches)")
            ActionRow("Free up space", "See downloads by size, spot the ones you already watched and delete several at once", Icons.Default.Delete) { onPage("space") }
            ActionRow("Downloaded movies: ${fmtSize(usage.downloads)}", "Manage them in the Downloads tab. Clearing caches never deletes them.", Icons.Default.Download) {}
            Group("Limits & automatic cleanup")
            ChoiceRow("Poster cache limit (applies next start)", p.posterCacheMb, listOf(100, 200, 300, 500, 1000).map { it to "$it MB" }) { vm.set(K.posterCacheMb, it) }
            ChoiceRow("Temporary files limit", p.tempCacheMb, listOf(512, 1024, 2048, 4096, 8192).map { it to fmtSize(it * 1024L * 1024L) }) { vm.set(K.tempCacheMb, it) }
            SwitchRow("Automatic cleanup", "Clears cached data after ${p.cacheDays} day(s) without opening the app", p.autoClean) { vm.set(K.autoClean, it) }
            ChoiceRow("Keep cache for", p.cacheDays, listOf(1, 3, 7, 15, 30).map { it to (if (it == 1) "1 day" else "$it days") }) { vm.set(K.cacheDays, it) }
            ChoiceRow("Clear movie data after watching", p.clearAfterWatch,
                listOf("auto" to "Only when storage is low (under 5 GB)", "always" to "Always", "never" to "Never")) { vm.set(K.clearAfterWatch, it) }
            if (CacheManager.isLowStorage(ctx)) Text("Your phone is low on storage (${fmtSize(CacheManager.freeBytes(ctx))} free).", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Group("Total")
            Column(Modifier.padding(16.dp)) {
                Text("Caches: ${fmtSize(usage.cacheTotal)}   •   Downloads: ${fmtSize(usage.downloads)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                StorageBar(usage.cacheTotal + usage.downloads, maxOf(usage.cacheTotal + usage.downloads, 1L) * 2)
            }
        }

        else -> Page("Help & Support", onBack) {
            var guide by remember { mutableStateOf(false) }
            if (guide) OtpGuideDialog(p.uiLang, { vm.set(K.uiLang, it) }) { guide = false }
            Group("Login")
            ActionRow("Login code not coming?", "Step-by-step guide in English and മലയാളം", Icons.Default.HelpOutline) { guide = true }
            ActionRow("Open Telegram", "See your login code in the Telegram app", Icons.Default.Send) { if (!openTelegram(ctx)) vm.say("Telegram is not installed") }
            Group("How it works")
            ActionRow("Where do my movies come from?", "NWMovie scans the Telegram chats and channels you already have access to and keeps only long videos and large video files.") {}
            ActionRow("A movie has the wrong poster", "Open it → “Wrong or missing match?” → search and pick the correct movie.") {}
            ActionRow("Video is slow to start", "Streaming depends on Telegram's speed. A loading spinner shows while data arrives. Downloading first removes buffering.") {}
            ActionRow("Video won't play", "Some codecs (e.g. DTS/E-AC3 audio, AVI containers) aren't supported on every phone. Try turning hardware acceleration off, or download and open in another player.") {}
            ActionRow("Legal", "Use NWMovie only with content you own or are authorised to view. Follow Telegram's Terms of Service and copyright laws in your country.") {}
        }
    }
}

@Composable
private fun CacheRow(title: String, bytes: Long, note: String? = null, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(fmtSize(bytes) + if (note != null) "  •  $note" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onClear, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Text("Clear") }
    }
}

// ---------------------------------------------------------------- statistics

private class WatchStats(val total: Long, val genres: List<Pair<String, Long>>, val top: List<Pair<MovieGroup, Long>>, val movies: Int, val episodes: Int)

private fun fmtHours(ms: Long): String { val h = ms / 3_600_000; val m = (ms % 3_600_000) / 60_000; return if (h > 0) "${h}h ${m}m" else "${m}m" }

@Composable
private fun ColumnScope.StatsContent(vm: MainViewModel) {
    val movies by vm.movies.collectAsState()
    val groups by vm.groups.collectAsState()
    val st = remember(movies, groups) {
        val genres = HashMap<String, Long>()
        movies.forEach { m -> if (m.watchedMs > 0) m.genreList.forEach { g -> genres[g] = (genres[g] ?: 0L) + m.watchedMs } }
        WatchStats(
            movies.sumOf { it.watchedMs },
            genres.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value },
            groups.map { g -> g to g.files.sumOf { it.watchedMs } }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5),
            groups.count { !it.isSeries && it.files.any { f -> f.watched } },
            movies.count { it.isSeries && it.watched }
        )
    }
    Column(Modifier.padding(16.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Total watch time", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmtHours(st.total), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                Text("${st.movies} movies finished  •  ${st.episodes} episodes finished", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (st.total == 0L) Text("Nothing yet. Watch time is counted from the moment this version is installed.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        if (st.genres.isNotEmpty()) {
            Text("Favourite genres", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
            val max = st.genres.first().second.toFloat()
            st.genres.forEach { (g, ms) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                    Text(g, Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.weight(1f).height(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(ms / max).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                    Text(fmtHours(ms), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
        if (st.top.isNotEmpty()) {
            Text("Most watched", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
            st.top.forEachIndexed { i, (g, ms) ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(28.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    Text(g.view.displayTitle, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(fmtHours(ms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- free up space

@Composable
private fun ColumnScope.SpaceContent(vm: MainViewModel) {
    val dls by vm.downloads.collectAsState()
    val movies by vm.movies.collectAsState()
    val done = remember(dls) { dls.map { it.first }.filter { it.state == DownloadItem.DONE }.sortedByDescending { it.total } }
    val watched = remember(movies) { movies.filter { it.watched }.map { it.id }.toSet() }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val selSize = done.filter { it.id in sel }.sumOf { it.total }
    val watchedSize = done.filter { it.id in watched }.sumOf { it.total }

    Column(Modifier.padding(16.dp)) {
        Text("${done.size} downloaded  •  ${fmtSize(done.sumOf { it.total })} used", style = MaterialTheme.typography.titleMedium)
        if (done.isEmpty()) Text("No downloaded movies. Nothing to free up here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        else {
            Text("${done.count { it.id in watched }} already watched (${fmtSize(watchedSize)} can be freed)", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sel = done.filter { it.id in watched }.map { it.id }.toSet() }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Select watched") }
                OutlinedButton(onClick = { sel = done.map { it.id }.toSet() }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Select all") }
                TextButton(onClick = { sel = emptySet() }) { Text("Clear") }
            }
            Spacer(Modifier.height(8.dp))
            done.forEach { d ->
                Row(Modifier.fillMaxWidth().clickable { sel = if (d.id in sel) sel - d.id else sel + d.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = d.id in sel, onCheckedChange = { sel = if (it) sel + d.id else sel - d.id })
                    Column(Modifier.weight(1f)) {
                        Text(d.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtSize(d.total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (d.id in watched) Text("  •  Watched", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { confirm = true }, enabled = sel.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("Delete ${sel.size} selected  •  ${fmtSize(selSize)}")
            }
        }
    }
    if (confirm) Confirm("Delete ${sel.size} download(s)?", "${fmtSize(selSize)} will be freed. The movies stay in Telegram and can be downloaded again.", "Delete",
        { vm.deleteMany(sel); sel = emptySet() }, { confirm = false })
}

// ---------------------------------------------------------------- updates

@Composable
private fun ColumnScope.UpdatesContent(vm: MainViewModel) {
    val st by Updater.state.collectAsState()
    Column(Modifier.padding(16.dp)) {
        Text("Installed version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${BuildConfig.VERSION_NAME}  (build ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            when (val s = st) {
                is Updater.State.Checking -> "Checking…"
                is Updater.State.UpToDate -> "You have the latest version."
                is Updater.State.NotConfigured -> "Updates are not set up in this build."
                is Updater.State.Available -> "Version ${s.info.versionName} is available."
                is Updater.State.Failed -> s.message
                else -> if (Updater.configured) "Tap the button to look for a new version." else "Updates are not set up in this build."
            }, style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.checkUpdates(true) }, enabled = Updater.configured, shape = RoundedCornerShape(14.dp)) { Text("Check for updates") }
        val ctx = LocalContext.current
        val fp = remember { Integrity.fingerprint(ctx) }
        if (fp != null) {
            Text("Signing fingerprint (SHA-256)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
            Text(fp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("fingerprint", fp))
                vm.say("Fingerprint copied")
            }, modifier = Modifier.padding(top = 6.dp)) { Text("Copy fingerprint") }
            Text("Use this value as APP_CERT_SHA256 in Codemagic (one-time setup).", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Text("Updates are signed by the developer and checked before they are installed. “Later” asks again the next day.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    }
}
