package app.nwmovie.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nwmovie.BuildConfig
import app.nwmovie.MainViewModel
import app.nwmovie.data.K
import app.nwmovie.data.Telemetry
import kotlinx.coroutines.launch
import java.util.Locale

/** Shows the maintenance screen or the developer's announcement when the dashboard has switched one on. */
@Composable
fun RemoteHost(vm: MainViewModel) {
    val r by Telemetry.remote.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    if (r.maintenanceOn) {
        BackHandler(true) { }
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clickable(enabled = false) { },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Default.Build, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(r.maintenanceTitle.ifBlank { "We'll be right back" }, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    r.maintenanceMessage.ifBlank { "NWFlix is being updated. Please try again in a little while." },
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { scope.launch { Telemetry.refresh() } }) { Text("Try again") }
            }
        }
        return
    }

    val now = System.currentTimeMillis()
    val country = Locale.getDefault().country
    val show = r.annId.isNotBlank() && prefs.annSeen != r.annId &&
        (r.annExpiresAt == 0L || now < r.annExpiresAt) &&
        (r.annMinVersion == 0 || BuildConfig.VERSION_CODE >= r.annMinVersion) &&
        (r.annMaxVersion == 0 || BuildConfig.VERSION_CODE <= r.annMaxVersion) &&
        (r.annCountry.isBlank() || r.annCountry.split(',').any { it.trim().equals(country, ignoreCase = true) })

    if (show) {
        val close = { vm.set(K.annSeen, r.annId) }
        AlertDialog(
            onDismissRequest = { if (r.annDismissible) close() },
            title = { Text(r.annTitle.ifBlank { "NWFlix" }) },
            text = { Text(r.annMessage) },
            confirmButton = {
                if (r.annButtonText.isNotBlank() && r.annButtonUrl.startsWith("https://")) {
                    Button(onClick = {
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.annButtonUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                        close()
                    }) { Text(r.annButtonText) }
                } else TextButton(onClick = { close() }) { Text("OK") }
            },
            dismissButton = {
                if (r.annButtonText.isNotBlank() && r.annButtonUrl.startsWith("https://")) TextButton(onClick = { close() }) { Text(if (r.annDismissible) "Later" else "OK") }
            }
        )
    }
}
