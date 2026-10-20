package app.nwmovie.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nwmovie.data.Updater
import app.nwmovie.util.fmtSize
import kotlinx.coroutines.launch

@Composable
fun UpdateHost() {
    val st by Updater.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val required = when (val s = st) {
        is Updater.State.Available -> s.info.mandatory
        is Updater.State.NeedPermission -> s.info.mandatory
        is Updater.State.Downloading -> s.info.mandatory
        is Updater.State.Installing -> s.info.mandatory
        is Updater.State.Failed -> s.blocking
        else -> false
    }

    // A required update covers the whole app until it is installed.
    if (required) {
        BackHandler(true) { }
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .clickable(enabled = false) { },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text("Update required", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Please install the latest version to keep using NWFlix.",
                    Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    when (val s = st) {
        is Updater.State.Available -> AlertDialog(
            onDismissRequest = { if (!s.info.mandatory) scope.launch { Updater.later() } },
            title = { Text("Update available") },
            text = {
                Column {
                    Text(
                        "NWFlix ${s.info.versionName}" + if (s.info.size > 0) "  (${fmtSize(s.info.size)})" else "",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (s.info.notes.isNotBlank()) Text(s.info.notes, Modifier.padding(top = 8.dp))
                    if (s.info.mandatory) Text(
                        "This update is required to keep using NWFlix.",
                        Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = { Button(onClick = { scope.launch { Updater.downloadAndInstall(ctx, s.info) } }) { Text("Download & install") } },
            dismissButton = { if (!s.info.mandatory) TextButton(onClick = { scope.launch { Updater.later() } }) { Text("Not now") } }
        )
        is Updater.State.NeedPermission -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Allow installing updates") },
            text = { Text("Android needs your permission once so NWFlix can install its updates. Tap Allow, switch on “Allow from this source”, then come back. The update continues by itself.") },
            confirmButton = { Button(onClick = { Updater.openInstallPermissionScreen(ctx) }) { Text("Allow") } },
            dismissButton = { if (!s.info.mandatory) TextButton(onClick = { scope.launch { Updater.later() } }) { Text("Cancel") } }
        )
        is Updater.State.Downloading -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Downloading update") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${s.percent}%", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { }
        )
        is Updater.State.Installing -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Installing…") },
            text = { Text("NWFlix restarts when the update is installed. If Android asks, tap Update.") },
            confirmButton = { }
        )
        is Updater.State.Failed -> AlertDialog(
            onDismissRequest = { if (!s.blocking) Updater.state.value = Updater.State.Idle },
            title = { Text(if (s.blocking) "Update required" else "Update problem") },
            text = { Text(s.message) },
            confirmButton = {
                when {
                    s.info != null -> Button(onClick = { scope.launch { Updater.downloadAndInstall(ctx, s.info) } }) { Text("Try again") }
                    s.blocking -> Button(onClick = { scope.launch { Updater.check(ctx, true) } }) { Text("Try again") }
                    else -> TextButton(onClick = { Updater.state.value = Updater.State.Idle }) { Text("OK") }
                }
            },
            dismissButton = { if (!s.blocking && s.info != null) TextButton(onClick = { Updater.state.value = Updater.State.Idle }) { Text("Close") } }
        )
        else -> Unit
    }
}
