package app.nwmovie.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nwmovie.MainViewModel
import app.nwmovie.data.MovieGroup
import app.nwmovie.data.db.DownloadItem
import app.nwmovie.data.db.Movie
import app.nwmovie.data.fileTags
import app.nwmovie.data.groupKey
import app.nwmovie.data.tmdb.TmdbClient
import app.nwmovie.ui.components.GradientScrim
import app.nwmovie.ui.components.RatingChip
import app.nwmovie.util.fmtRuntime
import app.nwmovie.util.fmtSize
import app.nwmovie.util.fmtTime
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.json.JSONArray

@Composable
fun DetailScreen(vm: MainViewModel, id: String, onBack: () -> Unit, onPlay: (String) -> Unit) {
    val movies by vm.movies.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val prefs by vm.prefs.collectAsState()
    // All files of the same movie are merged here.
    val group = remember(movies, id) {
        val m = movies.firstOrNull { it.id == id }
        if (m == null) null else { val k = m.groupKey(); MovieGroup(movies.filter { it.groupKey() == k }) }
    }
    var fixOpen by remember { mutableStateOf(false) }
    val m = group?.view

    LaunchedEffect(m?.tmdbId, m?.cast) { if (m != null && m.matched && m.cast == "[]") vm.loadDetails(m) }

    if (group == null || m == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                val back = TmdbClient.img(m.backdrop, if (prefs.bandwidthSaver) "w500" else "w780") ?: TmdbClient.img(m.poster, if (prefs.bandwidthSaver) "w342" else "w500")
                if (back != null) AsyncImage(back, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                GradientScrim(Modifier.fillMaxSize())
                Row(Modifier.statusBarsPadding().padding(8.dp)) {
                    FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Spacer(Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { vm.toggleFav(m) }) {
                        Icon(if (m.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favourite",
                            tint = if (m.favorite) Color(0xFFFF6B8A) else LocalContentColor.current)
                    }
                }
                Text(m.displayTitle, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val bits = listOf(if (m.displayYear > 0) m.displayYear.toString() else "", m.genreList.take(3).joinToString(" • "), fmtRuntime(m.runtime))
                    Text(bits.filter { it.isNotBlank() }.joinToString("  •  "), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (m.rating > 0) RatingChip(m.rating)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onPlay(if (prefs.bandwidthSaver && group.resumeFile == null) group.smallestFile.id else group.playFile.id) }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (group.resumeFile != null) "Resume" else "Play")
                    }
                    OutlinedButton(onClick = { vm.toggleWatch(m) }, modifier = Modifier.height(50.dp), shape = RoundedCornerShape(12.dp)) {
                        Icon(if (m.watchlist) Icons.Default.Check else Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("My List")
                    }
                }
                if (prefs.bandwidthSaver && group.fileCount > 1 && group.resumeFile == null) Text(
                    "Data saver is on: Play uses the smallest file (${fmtSize(group.smallestFile.size)}).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(16.dp))
                if (m.overview.isNotBlank()) Text(m.overview, style = MaterialTheme.typography.bodyLarge)
                else if (!m.matched) Text("This movie hasn't been matched yet. Tap “Wrong or missing match?” below to pick the right one.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val cast = parseCast(m.cast)
        if (cast.isNotEmpty()) item {
            Column {
                Text("Cast", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(cast) { c ->
                        Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                val u = TmdbClient.img(c.third, if (prefs.bandwidthSaver) "w92" else "w185")
                                if (u != null) AsyncImage(u, c.first, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(c.first, style = MaterialTheme.typography.bodySmall, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                            Text(c.second, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // ---- every file available for this movie ----
        item {
            Text(if (group.fileCount > 1) "Available files (${group.fileCount})" else "File", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp, 24.dp, 16.dp, 6.dp))
        }
        items(group.files.sortedByDescending { it.size }, key = { it.id }) { f ->
            FileRow(f, downloads.firstOrNull { it.first.id == f.id }, onPlay = { onPlay(f.id) }, onDownload = { vm.download(f) })
        }

        item {
            Column(Modifier.padding(16.dp, 20.dp)) {
                TextButton(onClick = { fixOpen = true }, contentPadding = PaddingValues(0.dp)) { Text("Wrong or missing match?") }
                Text("Metadata and images by TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (fixOpen) FixMatchDialog(vm, m) { fixOpen = false }
}

@Composable
private fun FileRow(f: Movie, dl: Pair<DownloadItem, Long>?, onPlay: () -> Unit, onDownload: () -> Unit) {
    val tags = remember(f.id, f.fileName, f.caption) { fileTags(f) }
    Surface(
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (tags.isEmpty()) "Video file" else tags.joinToString("  •  "), style = MaterialTheme.typography.titleMedium)
                    val meta = listOfNotNull(fmtSize(f.size), f.durationSec.takeIf { it > 0 }?.let { fmtTime(it * 1000L) }).joinToString("  •  ")
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, "Play this file") }
                val state = dl?.first?.state
                val total = dl?.first?.total ?: 0L
                val done = dl?.second ?: 0L
                when (state) {
                    DownloadItem.DONE -> Icon(Icons.Default.DownloadDone, "Downloaded", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 10.dp))
                    DownloadItem.DOWNLOADING, DownloadItem.PAUSED ->
                        Text("${if (total > 0) (done * 100 / total).toInt() else 0}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                    else -> IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "Download this file") }
                }
            }
            if (f.fileName.isNotBlank()) Text(f.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            if (f.positionMs > 5000 && f.durationMs > 0 && !f.watched) {
                LinearProgressIndicator(progress = { (f.positionMs.toFloat() / f.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("Resume from ${fmtTime(f.positionMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun parseCast(json: String): List<Triple<String, String, String>> = try {
    val a = JSONArray(json)
    (0 until a.length()).map { val o = a.getJSONObject(it); Triple(o.optString("n"), o.optString("c"), o.optString("p")) }
} catch (e: Exception) { emptyList() }

@Composable
private fun FixMatchDialog(vm: MainViewModel, m: Movie, onClose: () -> Unit) {
    var q by remember { mutableStateOf(m.parsedTitle) }
    var year by remember { mutableStateOf(if (m.parsedYear > 0) m.parsedYear.toString() else "") }
    var results by remember { mutableStateOf<List<TmdbClient.Hit>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Pick the right movie") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Year (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(onClick = { loading = true; scope.launch { results = vm.searchTmdb(q, year.toIntOrNull() ?: 0); loading = false } }) { Text("Search") }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                results?.let { r ->
                    if (r.isEmpty() && !loading) Text("No results. Try a shorter title.", style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        r.forEach { h ->
                            ListItem(
                                headlineContent = { Text(h.title) }, supportingContent = { Text(if (h.year > 0) h.year.toString() else "—") },
                                modifier = Modifier.clickable { vm.applyMatch(m, h.id); onClose() },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}
