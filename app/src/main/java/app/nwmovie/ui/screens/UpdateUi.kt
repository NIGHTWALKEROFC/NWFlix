package app.nwmovie.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.nwmovie.data.Updater
import app.nwmovie.ui.BrandMark
import app.nwmovie.ui.theme.NWButton
import kotlinx.coroutines.launch

private const val PERMISSION_TEXT = "Android needs your permission once so NWFlix can install its updates. Tap Allow, switch on “Allow from this source”, then come back. The download starts by itself."

@Composable
fun UpdateHost() {
    val st by Updater.state.collectAsState()
    val hidden by Updater.hidden.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = Updater.infoOf(st)
    var welcome by remember { mutableStateOf(Updater.pendingWelcome()) }
    welcome?.let { w -> WelcomeDialog(w) { Updater.welcomeSeen(); welcome = null } }

    if (Updater.isBlocking(st) && info != null) {
        BackHandler(true) { }
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clickable(enabled = false) { }
                .statusBarsPadding().navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BrandMark(64)
                Spacer(Modifier.height(20.dp))
                Text("Update required", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Version ${info.versionName} needs to be installed to keep using NWFlix.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                )
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(info.notes, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(24.dp))
                when (val s = st) {
                    is Updater.State.Available -> NWButton(onClick = { Updater.begin(ctx, s.info) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Update now") }
                    is Updater.State.NeedPermission -> {
                        Text(PERMISSION_TEXT, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        NWButton(onClick = { Updater.openInstallPermissionScreen(ctx) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Allow") }
                    }
                    is Updater.State.Downloading -> {
                        if (s.percent >= 0) LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(if (s.percent >= 0) "Downloading… ${s.percent}%" else "Downloading…", Modifier.padding(top = 8.dp))
                        Text("You can leave the app. The download continues.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is Updater.State.Installing -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Installing… If Android asks, tap Install.", Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
                    }
                    is Updater.State.Ready -> {
                        if (s.note.isNotBlank()) Text(s.note, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        NWButton(onClick = { Updater.install(ctx, s) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Install now") }
                    }
                    is Updater.State.Failed -> {
                        Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        NWButton(onClick = { Updater.begin(ctx, info) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Try again") }
                    }
                    else -> Unit
                }
            }
        }
        return
    }

    when (val s = st) {
        is Updater.State.Available -> AlertDialog(
            onDismissRequest = { scope.launch { Updater.later() } },
            title = { Text("Update available") },
            text = {
                Column {
                    Text("NWFlix ${s.info.versionName}", style = MaterialTheme.typography.titleMedium)
                    if (s.info.notes.isNotBlank()) Text(s.info.notes, Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { NWButton(onClick = { Updater.begin(ctx, s.info) }) { Text("Update now") } },
            dismissButton = { TextButton(onClick = { scope.launch { Updater.later() } }) { Text("Not now") } }
        )
        is Updater.State.NeedPermission -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Allow installing updates") },
            text = { Text(PERMISSION_TEXT) },
            confirmButton = { NWButton(onClick = { Updater.openInstallPermissionScreen(ctx) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { Updater.dismiss() }) { Text("Cancel") } }
        )
        is Updater.State.Downloading -> if (!hidden) AlertDialog(
            onDismissRequest = { },
            title = { Text("Downloading update") },
            text = {
                Column {
                    if (s.percent >= 0) LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(if (s.percent >= 0) "${s.percent}%" else "Please wait…", Modifier.padding(top = 8.dp))
                    Text("You can leave the app. The download continues and you get a notification when it is ready.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { Updater.hidden.value = true }) { Text("Hide") } }
        )
        is Updater.State.Installing -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Installing…") },
            text = { Text("NWFlix restarts when the update is installed. If Android asks, tap Install.") },
            confirmButton = { }
        )
        is Updater.State.Ready -> AlertDialog(
            onDismissRequest = { scope.launch { Updater.later() } },
            title = { Text("Update ready") },
            text = { Text(s.note.ifBlank { "NWFlix ${s.info.versionName} is downloaded and ready to install." }) },
            confirmButton = { NWButton(onClick = { Updater.install(ctx, s) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { scope.launch { Updater.later() } }) { Text("Later") } }
        )
        is Updater.State.Failed -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            title = { Text("Update problem") },
            text = { Text(s.message) },
            confirmButton = {
                if (s.info != null) NWButton(onClick = { Updater.begin(ctx, s.info) }) { Text("Try again") }
                else TextButton(onClick = { Updater.dismiss() }) { Text("OK") }
            },
            dismissButton = { if (s.info != null) TextButton(onClick = { Updater.dismiss() }) { Text("Close") } }
        )
        else -> Unit
    }
}


@Composable
private fun WelcomeDialog(w: Updater.Welcome, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BrandMark(72)
                Spacer(Modifier.height(20.dp))
                Text(if (w.firstTime) "Welcome to NWFlix" else "Welcome back", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (w.firstTime) "Your movies, your way" else "You are now on version ${w.version}",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    if (w.firstTime) "Stream your Telegram movies in one beautiful place. Browse your library, play instantly, download to watch offline, and pick up right where you stopped. Grab some popcorn and enjoy the show."
                    else "Thanks for staying with us. NWFlix has been updated to make your movie nights smoother. We hope you love it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                )
                if (w.notes.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text("What's new", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(w.notes, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(24.dp))
                NWButton(onClick = onClose, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (w.firstTime) "Let's start" else "Continue") }
            }
        }
    }
}
