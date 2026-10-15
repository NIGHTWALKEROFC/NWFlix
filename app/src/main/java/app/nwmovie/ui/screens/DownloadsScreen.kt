package app.nwmovie.ui.screens

import android.os.StatFs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nwmovie.MainViewModel
import app.nwmovie.ServiceLocator
import app.nwmovie.data.CacheManager
import app.nwmovie.data.db.DownloadItem
import app.nwmovie.data.tmdb.TmdbClient
import app.nwmovie.ui.components.EmptyState
import app.nwmovie.ui.components.StorageBar
import app.nwmovie.util.fmtSize
import coil.compose.AsyncImage

@Composable
fun DownloadsScreen(vm: MainViewModel, onPlay: (String) -> Unit) {
    val items by vm.downloads.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val running = items.filter { it.first.state != DownloadItem.DONE }
    val done = items.filter { it.first.state == DownloadItem.DONE }
    val list = if (tab == 0) running else done
    val used = remember(items) { CacheManager.dirSize(ServiceLocator.downloads.dir) }
    val stat = remember { StatFs(ctx.filesDir.path) }
    val total = stat.totalBytes
    var confirm by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Downloads", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp, 8.dp))
        TabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Downloading (${running.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Offline (${done.size})") })
        }
        if (list.isEmpty()) EmptyState(Icons.Default.Download, if (tab == 0) "Nothing downloading" else "No offline movies",
            "Open a movie and tap the download button to watch it without internet.", Modifier.weight(1f).padding(top = 48.dp))
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.first.id }) { (d, bytes) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val u = TmdbClient.img(d.poster, "w185")
                    Box(Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
                        if (u != null) AsyncImage(u, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(Icons.Default.Movie, null, Modifier.align(Alignment.Center))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(d.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (d.state == DownloadItem.DONE) Text(fmtSize(d.total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else {
                            val pct = if (d.total > 0) (bytes * 100 / d.total).toInt() else 0
                            Text(when (d.state) {
                                DownloadItem.DOWNLOADING -> "Downloading… $pct% (${fmtSize(bytes)} / ${fmtSize(d.total)})"
                                DownloadItem.PAUSED -> "Paused • $pct%"
                                DownloadItem.ERROR -> "Failed — tap resume to retry"
                                else -> "Waiting…"
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(progress = { if (d.total > 0) (bytes.toFloat() / d.total).coerceIn(0f, 1f) else 0f },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                    }
                    if (d.state == DownloadItem.DONE) {
                        IconButton(onClick = { onPlay(d.id) }) { Icon(Icons.Default.PlayArrow, "Play") }
                        IconButton(onClick = { confirm = d.id }) { Icon(Icons.Default.Delete, "Delete") }
                    } else {
                        if (d.state == DownloadItem.DOWNLOADING) IconButton(onClick = { vm.pauseDl(d.id) }) { Icon(Icons.Default.Pause, "Pause") }
                        else IconButton(onClick = { vm.resumeDl(d.id) }) { Icon(Icons.Default.PlayArrow, "Resume") }
                        IconButton(onClick = { vm.cancelDl(d.id) }) { Icon(Icons.Default.Close, "Cancel") }
                    }
                }
            }
        }
        Column(Modifier.padding(16.dp)) {
            Row { Text("Storage used by downloads", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text("${fmtSize(used)} / ${fmtSize(total)}", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(6.dp))
            StorageBar(used, total)
        }
    }
    confirm?.let { id ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Delete download?") },
            text = { Text("The offline copy will be removed from this phone. The movie stays in Telegram.") },
            confirmButton = { TextButton(onClick = { vm.deleteDl(id); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}
