package app.nwmovie.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private class LegalDoc(val title: String, val sections: List<Pair<String, List<String>>>)

private const val LEGAL_UPDATED = "October 2026"

private val LEGAL: Map<String, LegalDoc> = mapOf(
    "privacy" to LegalDoc("Privacy Policy", listOf(
        "Who we are" to listOf(
            "NWFlix is an Android app made by Mohammed Rinshan (NIGHTWALKER). This policy explains what the app does with information. Questions can be sent from the app (About → Contact & report a bug) or from the website.",
        ),
        "What NWFlix is" to listOf(
            "NWFlix does not host, store or upload movies. It shows the video files that are already in your own Telegram account, so you can watch them in a nicer player.",
        ),
        "Information that stays on your phone" to listOf(
            "Your Telegram login session is handled by Telegram's official TDLib library. It is stored in an encrypted database on your phone and is never sent to the developer.",
            "The list of movies found in your chats, your watch progress, My List, favourites, settings, downloads and temporary playback files are stored only on your phone.",
            "NWFlix never reads or sends the text of your messages, your contacts or your phone number to the developer.",
        ),
        "Information sent to other services" to listOf(
            "Telegram: the app connects to Telegram to log you in, list your chats and stream the files you choose. Telegram's own privacy policy applies to that.",
            "TMDB (The Movie Database): to show posters and details, the app sends the cleaned-up movie title and year taken from a file name to TMDB. No account details are sent. TMDB's privacy policy applies.",
            "App updates: the update details come from Google Firebase, and the update file is downloaded from the link the developer sets (for example GitHub). The host of that link can see your IP address when you download an update.",
        ),
        "Google sign-in and usage statistics" to listOf(
            "To use NWFlix you sign in with your Google account (one tap, no password). This is used to count real users and to be able to contact users with important notices.",
            "When you sign in, Google and Google Firebase give the developer your Google account email address and a random account ID. Nothing else from your Google account is read.",
            "While you are signed in and statistics are on, the app sends a small record to Google Firebase: the random account ID, app version, Android version, phone model, the country and language set on your phone, the time you last opened the app and how many times you opened it.",
            "It never includes your Telegram number or username, chat names, file names, movie titles or what you watch.",
            "You can switch the statistics off at any time in Settings → Privacy & Security → Share anonymous usage statistics, and sign out of Google in Settings → Account & Security. Signing out stops the records.",
        ),
        "The download website" to listOf(
            "The website only serves the app file and these documents. It does not use cookies or analytics. The download page loads the current version and file links from Google Firebase. If you type your phone's name to find the right file, that name is sent to Wikipedia to look it up. Google and Wikipedia can see your IP address when this happens.",
        ),
        "Messages from the developer" to listOf(
            "The app checks a small public notice from time to time. It can show a message (for example about an update) or tell you the app is under maintenance. This notice is the same for everyone and does not use your personal information.",
        ),
        "Your choices and deleting data" to listOf(
            "You can clear the movie index, saved movie details, posters, temporary files and downloads in Settings → Storage & Cache, or log out and delete everything NWFlix stored in Settings → Account & Security. Uninstalling the app removes all data stored on your phone.",
            "To ask for your Google account record and statistics to be deleted, contact the developer from the app. Where the law of your country gives you rights over your information (for example access, correction or deletion), you can use them by contacting the developer.",
        ),
        "Children" to listOf(
            "NWFlix is not meant for children under 13 (or under the age required by Telegram in your country). Do not use the app if you are below that age.",
        ),
        "Security" to listOf(
            "Sensitive keys are stored in the Android Keystore. An update is only installed if it is signed with the developer's key, and Android refuses any update signed by someone else. No system that connects to the internet can be made 100 percent secure.",
        ),
        "Changes to this policy" to listOf(
            "This policy may change when the app changes. The date at the top shows when it was last updated (October 2026). Continuing to use the app after a change means you accept the new policy.",
        ),
    )),
    "terms" to LegalDoc("Terms & Conditions", listOf(
        "Acceptance" to listOf(
            "By installing or using NWFlix you agree to these terms. If you do not agree, do not use the app. NWFlix is provided by Mohammed Rinshan (NIGHTWALKER).",
        ),
        "What you may do" to listOf(
            "You may use NWFlix for personal, non-commercial use to view video files that you have in your own Telegram account.",
        ),
        "Your responsibility for content" to listOf(
            "NWFlix does not provide, host or check any movies. You are fully responsible for the files you open and for making sure you have the right to watch them.",
            "Use the app only with content you own, content that is free to share, or content you have permission to watch. Do not use it to break copyright law or the terms of Telegram.",
        ),
        "What you may not do" to listOf(
            "Copy, sell, rent or re-publish the app, remove its credits, or change it and pass it on as your own.",
            "Try to break, overload or misuse the app, its update system or the services it uses.",
            "Use the app for anything illegal.",
        ),
        "Other services" to listOf(
            "NWFlix works with Telegram, TMDB, Google (sign-in) and Google Firebase. They have their own terms, which you must also follow. NWFlix is not affiliated with or endorsed by Telegram or TMDB.",
        ),
        "Updates, maintenance and messages" to listOf(
            "You must sign in with Google to use the app. The app can update itself and can show notices from the developer. Some updates may be required to keep using the app, and the app may be paused for maintenance.",
        ),
        "No warranty" to listOf(
            "NWFlix is provided \"as is\" and \"as available\", without any promise that it will work without errors, interruptions or on every phone.",
        ),
        "Limit of liability" to listOf(
            "To the greatest extent allowed by law, the developer is not responsible for any loss or damage that comes from using the app, from the content you watch, or from the services the app connects to.",
        ),
        "Ending use" to listOf(
            "You can stop using the app at any time by uninstalling it. The developer may stop, change or remove the app or block misuse.",
        ),
        "Changes and governing law" to listOf(
            "These terms may change; the date at the top shows the latest version (October 2026). They are governed by the laws of India, and courts in India decide disputes, unless the law of your country says otherwise.",
        ),
        "Contact" to listOf(
            "Questions about these terms can be sent from the app (About → Contact & report a bug).",
        ),
    )),
    "disclaimer" to LegalDoc("Disclaimer & Copyright", listOf(
        "No content is hosted" to listOf(
            "NWFlix does not host, store, upload, share or sell any movies, series or other media. It is a player that shows files that already exist in the user's own Telegram account.",
        ),
        "Copyright" to listOf(
            "The developer respects the rights of creators and copyright owners and does not support piracy. Users must only watch content they are allowed to watch.",
            "Because NWFlix has no content of its own, a copyright complaint about a file should be sent to the Telegram channel or group that shares it, or to Telegram through its official copyright reporting process.",
            "If you believe the NWFlix app itself breaks your rights, contact the developer from the app (About → Contact & report a bug) with details and proof of ownership, and it will be looked at promptly.",
        ),
        "Not affiliated" to listOf(
            "NWFlix is an independent app. It is not affiliated with, endorsed by or sponsored by Telegram. Telegram is a trademark of its owners.",
            "This product uses the TMDB API but is not endorsed or certified by TMDB. Movie information and posters come from TMDB.",
        ),
        "Open-source components" to listOf(
            "NWFlix uses Media3 ExoPlayer (Apache License 2.0), the VLC engine libVLC (LGPL 2.1) and Telegram's TDLib (Boost Software License). Their licences belong to their authors.",
        ),
        "Use at your own risk" to listOf(
            "Information shown in the app (ratings, descriptions, cast) comes from third parties and may be wrong. The developer is not responsible for what you watch or for decisions made from it.",
        ),
        "Developer" to listOf(
            "Mohammed Rinshan (NIGHTWALKER). © 2026. All rights reserved.",
        ),
    )),
)

fun legalTitle(kind: String): String = LEGAL[kind]?.title ?: ""

@Composable
fun LegalBody(kind: String) {
    val doc = LEGAL[kind] ?: return
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Last updated: $LEGAL_UPDATED", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        doc.sections.forEachIndexed { i, (heading, paras) ->
            Spacer(Modifier.height(16.dp))
            Text("${i + 1}. $heading", style = MaterialTheme.typography.titleMedium)
            paras.forEach { p -> Spacer(Modifier.height(6.dp)); Text(p, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun LegalDialog(kind: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(legalTitle(kind), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { LegalBody(kind) }
            }
        }
    }
}
