package app.nwmovie.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nwmovie.MainViewModel
import app.nwmovie.data.db.Movie
import app.nwmovie.data.tmdb.TmdbClient
import app.nwmovie.ui.components.EmptyState
import coil.compose.AsyncImage

/** "My List": Watchlist / History / Liked. */
@Composable
fun LibraryScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val groups by vm.groups.collectAsState()
    val movies = remember(groups) { groups.map { it.view } }
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Watchlist", "History", "Liked")
    val list = remember(movies, tab) {
        when (tab) {
            0 -> movies.filter { it.watchlist }.sortedByDescending { it.addedAt }
            1 -> movies.filter { it.lastPlayedAt > 0 }.sortedByDescending { it.lastPlayedAt }
            else -> movies.filter { it.favorite }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My List", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (tab == 1 && list.isNotEmpty()) TextButton(onClick = { vm.clearAllHistory() }) { Text("Clear all") }
        }
        TabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
            titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        if (list.isEmpty()) {
            EmptyState(
                Icons.Default.Bookmark,
                when (tab) { 0 -> "Your watchlist is empty"; 1 -> "No viewing history yet"; else -> "No liked movies yet" },
                when (tab) { 0 -> "Tap “My List” on a movie to save it for later."; 1 -> "Movies you watch will show up here."; else -> "Tap the heart on a movie to like it." },
                Modifier.padding(top = 48.dp)
            )
        } else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { m -> MovieRow(m, tab, onOpen, vm) }
        }
    }
}

@Composable
private fun MovieRow(m: Movie, tab: Int, onOpen: (String) -> Unit, vm: MainViewModel) {
    var menu by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(m.id) }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val u = TmdbClient.img(m.poster, "w185")
        androidx.compose.foundation.layout.Box(Modifier.width(60.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
            .then(Modifier)) {
            if (u != null) AsyncImage(u, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.Movie, null, Modifier.align(Alignment.Center))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(m.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(m.displayYear.takeIf { it > 0 }?.toString(), m.genreList.firstOrNull()).filterNotNull().joinToString(" • "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tab == 1 && m.durationMs > 0) LinearProgressIndicator(
                progress = { (m.positionMs.toFloat() / m.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.6f))
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; onOpen(m.id) })
                when (tab) {
                    0 -> DropdownMenuItem(text = { Text("Remove from list") }, onClick = { menu = false; vm.toggleWatch(m) })
                    1 -> DropdownMenuItem(text = { Text("Remove from history") }, onClick = { menu = false; vm.clearHistory(m.id) })
                    else -> DropdownMenuItem(text = { Text("Unlike") }, onClick = { menu = false; vm.toggleFav(m) })
                }
            }
        }
    }
}
