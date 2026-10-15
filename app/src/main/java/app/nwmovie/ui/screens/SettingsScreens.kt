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
fun SettingsPages(vm: MainViewModel, name: String, onBack: () -> Unit) {
    val p by vm.prefs.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    when (name) {
        "account" -> Page("Account & Security", onBack) {
            val user by vm.userName.collectAsState()
            var tmdb by remember { mutableStateOf(Secrets.tmdbKey) }
            var logout by remember { mutableStateOf(false) }
            Group("Telegram")
            ActionRow(user.ifBlank { "Telegram user" }, "Connected. Your session is encrypted on this phone.", Icons.Default.Person) {}
            ActionRow("Rescan chats (new movies)", "Looks for newly added movies", Icons.Default.Refresh) { vm.rescan(false); vm.say("Scanning…") }
            ActionRow("Full rescan & retry unmatched", "Slower: checks every chat deeply and re-matches titles", Icons.Default.Search) { vm.rescan(true); vm.say("Full scan started") }
            Group("Movie details (TMDB)")
            OutlinedTextField(tmdb, { tmdb = it }, label = { Text("TMDB API key (v3)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp))
            Button(onClick = { vm.saveTmdbKey(tmdb) }, modifier = Modifier.padding(horizontal = 16.dp), enabled = tmdb.isNotBlank()) { Text("Save key") }
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
            SwitchRow("Background playback", "Keep playing (audio) when you leave the app", p.bgPlayback) { vm.set(K.bgPlayback, it) }
            Group("Audio & subtitles")
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
            SwitchRow("Volume (swipe right side)", null, p.volumeGesture, p.gestures) { vm.set(K.volumeGesture, it) }
            SwitchRow("Seek (swipe sideways)", null, p.seekGesture, p.gestures) { vm.set(K.seekGesture, it) }
            SwitchRow("Remember brightness", "Keep the brightness you set in the player", p.rememberBrightness) { vm.set(K.rememberBrightness, it) }
            ChoiceRow("Double-tap seek", p.doubleTapSec, listOf(5, 10, 15, 30).map { it to "$it seconds" }) { vm.set(K.doubleTapSec, it) }
        }

        "network" -> Page("Network & Download", onBack) {
            Group("Mobile data")
            SwitchRow("Allow mobile data", "Off = NWMovie only uses Wi-Fi for everything", p.mobileData) { vm.set(K.mobileData, it) }
            SwitchRow("Wi-Fi only for streaming", null, p.wifiOnlyStream) { vm.set(K.wifiOnlyStream, it) }
            SwitchRow("Wi-Fi only for downloads", null, p.wifiOnlyDownload) { vm.set(K.wifiOnlyDownload, it) }
            Group("Data saving")
            SwitchRow("Bandwidth saver", "Smaller posters and less pre-loading while streaming", p.bandwidthSaver) { vm.set(K.bandwidthSaver, it) }
            SwitchRow("Downloads in background", "Off = downloads pause when you leave the app", p.bgDownloads) { vm.set(K.bgDownloads, it) }
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
            ActionRow("Downloaded movies: ${fmtSize(usage.downloads)}", "Manage them in the Downloads tab. Clearing caches never deletes them.", Icons.Default.Download) {}
            Group("Limits & automatic cleanup")
            ChoiceRow("Poster cache limit (applies next start)", p.posterCacheMb, listOf(100, 200, 300, 500, 1000).map { it to "$it MB" }) { vm.set(K.posterCacheMb, it) }
            ChoiceRow("Temporary files limit", p.tempCacheMb, listOf(512, 1024, 2048, 4096, 8192).map { it to fmtSize(it * 1024L * 1024L) }) { vm.set(K.tempCacheMb, it) }
            SwitchRow("Automatic cleanup", "Clears cached data after 3 days of inactivity", p.autoClean) { vm.set(K.autoClean, it) }
            Group("Total")
            Column(Modifier.padding(16.dp)) {
                Text("Caches: ${fmtSize(usage.cacheTotal)}   •   Downloads: ${fmtSize(usage.downloads)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                StorageBar(usage.cacheTotal + usage.downloads, maxOf(usage.cacheTotal + usage.downloads, 1L) * 2)
            }
        }

        else -> Page("Help & Support", onBack) {
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
