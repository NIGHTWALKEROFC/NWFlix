package app.nwmovie.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nwmovie.data.Updater
import app.nwmovie.util.fmtSize
import kotlinx.coroutines.launch

/** All update dialogs: update available (Later = ask again tomorrow), allow-installs prompt, download progress, errors. */
@Composable
fun UpdateHost() {
    val st by Updater.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    when (val s = st) {
        is Updater.State.Available -> AlertDialog(
            onDismissRequest = { if (!s.info.mandatory) scope.launch { Updater.later() } },
            title = { Text("Update available") },
            text = {
                Column {
                    Text("NWMovie ${s.info.versionName}" + if (s.info.size > 0) "  (${fmtSize(s.info.size)})" else "", style = MaterialTheme.typography.titleMedium)
                    if (s.info.notes.isNotBlank()) Text(s.info.notes, Modifier.padding(top = 8.dp))
                    Text("The version that fits your phone is downloaded and installed automatically.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    if (s.info.mandatory) Text("This update is required to keep using NWMovie.", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { Button(onClick = { scope.launch { Updater.downloadAndInstall(ctx, s.info) } }) { Text("Download & install") } },
            dismissButton = { if (!s.info.mandatory) TextButton(onClick = { scope.launch { Updater.later() } }) { Text("Later") } }
        )
        is Updater.State.NeedPermission -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Allow installing updates") },
            text = { Text("Android needs your permission once so NWMovie can install its own updates. Tap Allow, switch on “Allow from this source”, then come back. The update continues by itself.") },
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
            text = { Text("NWMovie will restart when the update is installed. If Android asks, tap Update.") },
            confirmButton = { }
        )
        is Updater.State.Failed -> AlertDialog(
            onDismissRequest = { Updater.state.value = Updater.State.Idle },
            title = { Text("Update problem") },
            text = { Text(s.message) },
            confirmButton = {
                if (s.info != null) Button(onClick = { scope.launch { Updater.downloadAndInstall(ctx, s.info) } }) { Text("Try again") }
                else TextButton(onClick = { Updater.state.value = Updater.State.Idle }) { Text("OK") }
            },
            dismissButton = { if (s.info != null) TextButton(onClick = { Updater.state.value = Updater.State.Idle }) { Text("Close") } }
        )
        else -> Unit
    }
}
