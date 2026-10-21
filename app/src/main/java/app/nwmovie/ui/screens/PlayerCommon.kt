package app.nwmovie.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nwmovie.ServiceLocator
import app.nwmovie.data.db.Movie
import app.nwmovie.data.groupKey

/** Parts and widgets used by both the standard player and the VLC player. */

internal fun playerTitle(m: Movie?, parts: Int, idx: Int): String {
    if (m == null) return ""
    var t = m.displayTitle
    if (m.isSeries) t += "  S%02dE%02d".format(m.season, m.episode)
    if (parts > 1) t += "  (Part ${idx + 1}/$parts)"
    return t
}

/**
 * Finds the Telegram file to stream for a movie. If that copy is gone (chat left, message deleted) but the same file
 * was forwarded to another chat, that copy is used instead.
 */
internal suspend fun resolveTgFile(f: Movie) = run {
    ServiceLocator.tg.refreshFile(f)?.let { f to it } ?: ServiceLocator.db.movies().allOnce()
        .filter { it.id != f.id && it.size == f.size && it.durationSec == f.durationSec && it.groupKey() == f.groupKey() }
        .firstNotNullOfOrNull { c -> ServiceLocator.tg.refreshFile(c)?.let { c to it } }
}

@Composable
internal fun PlayerBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Icon(icon, label, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun PlayerSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth(0.92f).heightIn(max = 380.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
            }
        }
    }
}

@Composable
internal fun PlayerOpt(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = if (enabled) onClick else null, enabled = enabled)
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PlayerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---------------------------------------------------------------- track names

private val LANGS = mapOf(
    "eng" to "English", "en" to "English", "hin" to "Hindi", "hi" to "Hindi", "mal" to "Malayalam", "ml" to "Malayalam",
    "tam" to "Tamil", "ta" to "Tamil", "tel" to "Telugu", "te" to "Telugu", "kan" to "Kannada", "kn" to "Kannada",
    "ben" to "Bengali", "bn" to "Bengali", "mar" to "Marathi", "mr" to "Marathi", "guj" to "Gujarati", "gu" to "Gujarati",
    "pan" to "Punjabi", "pa" to "Punjabi", "urd" to "Urdu", "ur" to "Urdu", "ara" to "Arabic", "ar" to "Arabic",
    "fre" to "French", "fra" to "French", "fr" to "French", "ger" to "German", "deu" to "German", "de" to "German",
    "spa" to "Spanish", "es" to "Spanish", "ita" to "Italian", "it" to "Italian", "por" to "Portuguese", "pt" to "Portuguese",
    "rus" to "Russian", "ru" to "Russian", "jpn" to "Japanese", "ja" to "Japanese", "kor" to "Korean", "ko" to "Korean",
    "chi" to "Chinese", "zho" to "Chinese", "zh" to "Chinese", "tha" to "Thai", "th" to "Thai", "ind" to "Indonesian", "id" to "Indonesian",
    "may" to "Malay", "msa" to "Malay", "ms" to "Malay", "tur" to "Turkish", "tr" to "Turkish", "vie" to "Vietnamese", "vi" to "Vietnamese",
    "sin" to "Sinhala", "si" to "Sinhala", "nep" to "Nepali", "ne" to "Nepali", "per" to "Persian", "fas" to "Persian", "fa" to "Persian",
    "heb" to "Hebrew", "he" to "Hebrew", "pol" to "Polish", "pl" to "Polish", "dut" to "Dutch", "nld" to "Dutch", "nl" to "Dutch",
    "swe" to "Swedish", "sv" to "Swedish", "gre" to "Greek", "ell" to "Greek", "el" to "Greek", "ukr" to "Ukrainian", "uk" to "Ukrainian"
)

/** "mal" → "Malayalam", "en-US" → "English". Returns null when the code says nothing ("und"). */
internal fun languageName(code: String?): String? {
    val c = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
    if (c.isEmpty() || c == "und" || c == "mis" || c == "zxx") return null
    LANGS[c]?.let { return it }
    val n = try { java.util.Locale(c).displayLanguage } catch (e: Exception) { "" }
    return n.takeIf { it.isNotBlank() && !it.equals(c, ignoreCase = true) }?.replaceFirstChar { it.uppercase() }
}

/** Name for an ExoPlayer audio or subtitle track: the full language name, plus any extra text the file gives. */
internal fun trackName(label: String?, lang: String?, n: Int): String {
    val l = languageName(lang)
    val lab = label?.trim().orEmpty()
    return when {
        l != null && (lab.isEmpty() || languageName(lab) != null || lab.equals(l, ignoreCase = true)) -> l
        l != null && lab.contains(l, ignoreCase = true) -> lab
        l != null -> "$l – $lab"
        lab.isNotEmpty() -> languageName(lab) ?: lab
        else -> "Track $n"
    }
}

/** Same for the VLC engine, whose names look like "Track 1 - [English]" or just "mal". */
internal fun vlcTrackName(name: String?, id: Int): String {
    val n = name?.trim().orEmpty()
    if (n.isEmpty()) return "Track $id"
    val br = Regex("\\[([^\\]]+)\\]").find(n)?.groupValues?.get(1)
    if (br != null) {
        val lang = languageName(br) ?: br
        val head = n.substringBefore('[').trim().trimEnd('-').trim()
        return if (head.isBlank() || head.startsWith("Track", ignoreCase = true)) lang else "$lang – $head"
    }
    return languageName(n) ?: n
}
