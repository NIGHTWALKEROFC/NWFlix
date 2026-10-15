package app.nwmovie.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nwmovie.MainViewModel
import app.nwmovie.data.db.Movie
import app.nwmovie.data.tmdb.TmdbClient
import app.nwmovie.ui.BrandMark
import app.nwmovie.ui.components.*
import app.nwmovie.util.fmtRuntime
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: MainViewModel, onOpen: (String) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    val movies by vm.movies.collectAsState()
    val scan by vm.scan.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val user by vm.userName.collectAsState()

    val matched = remember(movies) { movies.filter { it.matched } }
    val featured = remember(matched) { matched.filter { it.backdrop.isNotBlank() }.sortedByDescending { it.popularity }.take(5) }
    val cont = remember(movies) { movies.filter { it.lastPlayedAt > 0 && !it.watched && it.positionMs > 5000 }.sortedByDescending { it.lastPlayedAt } }
    val trending = remember(matched) { matched.sortedByDescending { it.popularity }.take(20) }
    val newest = remember(matched) { matched.sortedWith(compareByDescending<Movie> { it.year }.thenByDescending { it.addedAt }).take(20) }
    val recent = remember(movies) { movies.take(20) }
    val unmatched = remember(movies) { movies.filter { !it.matched } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(30)
                Spacer(Modifier.width(10.dp))
                Text("NWMOVIE", fontWeight = FontWeight.Light, fontSize = 20.sp, letterSpacing = 4.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
                Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center) {
                    Text(user.firstOrNull()?.uppercase() ?: "N", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (scan.running) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("${scan.phase}  •  ${scan.found} found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        scan.error?.let { e ->
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp, 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(e, style = MaterialTheme.typography.bodyMedium)
                        Row { TextButton(onClick = { vm.rescan(false) }) { Text("Retry") } }
                    }
                }
            }
        }

        if (movies.isEmpty()) {
            item {
                if (scan.running) EmptyState(Icons.Default.Movie, "Looking for your movies…", "Large chats can take a minute the first time.")
                else EmptyState(Icons.Default.Movie, "No movies found yet",
                    "NWMovie looks for long videos and big video files in the chats and channels you can access.",
                    action = "Scan again", onAction = { vm.rescan(true) })
            }
        } else {
            if (featured.isNotEmpty()) item { Hero(featured, vm, onOpen) }
            if (cont.isNotEmpty()) item { PosterRow(cont.take(15), "Continue Watching", prefs.showTitles, onOpen) }
            if (trending.isNotEmpty()) item { PosterRow(trending, "Trending Now", prefs.showTitles, onOpen) }
            if (newest.isNotEmpty()) item { PosterRow(newest, "New Releases", prefs.showTitles, onOpen) }
            item { PosterRow(recent, "Recently Added", prefs.showTitles, onOpen) }
            if (unmatched.isNotEmpty()) item { PosterRow(unmatched.take(30), "Needs a match (${unmatched.size})", true, onOpen) }
        }
    }
}

@Composable
private fun PosterRow(list: List<Movie>, title: String, showTitles: Boolean, onOpen: (String) -> Unit) {
    Column {
        SectionHeader(title)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.id }) { m -> Poster(m, Modifier.width(112.dp), showTitles) { onOpen(m.id) } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hero(list: List<Movie>, vm: MainViewModel, onOpen: (String) -> Unit) {
    val pager = rememberPagerState(pageCount = { list.size })
    LaunchedEffect(list.size) {
        while (list.size > 1) { delay(6000); pager.animateScrollToPage((pager.currentPage + 1) % list.size) }
    }
    HorizontalPager(pager, Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(230.dp), pageSpacing = 10.dp) { i ->
        val m = list[i]
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).clickable { onOpen(m.id) }) {
            AsyncImage(TmdbClient.img(m.backdrop, "w780"), m.displayTitle, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            GradientScrim(Modifier.fillMaxSize(), Color(0xFF070B1A))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(m.displayTitle, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(m.year.toString(), m.genreList.take(2).joinToString(" • "), fmtRuntime(m.runtime)).filter { it.isNotBlank() && it != "0" }.joinToString("  •  "),
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBD0F0))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onOpen(m.id) }, shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Watch Now")
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(onClick = { vm.toggleWatch(m) }) {
                        Icon(if (m.watchlist) Icons.Default.Check else Icons.Default.Add, "My List")
                    }
                }
            }
        }
    }
}
