package app.nwmovie.ui.screens

import app.nwmovie.ui.theme.NWButton
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
import app.nwmovie.data.MovieGroup
import app.nwmovie.data.SortOption
import app.nwmovie.data.sortedByOption
import app.nwmovie.data.tmdb.TmdbClient
import app.nwmovie.ui.BrandMark
import app.nwmovie.ui.components.*
import app.nwmovie.util.fmtRuntime
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Calendar

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: MainViewModel, onOpen: (String) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit, onSeeAll: (SortOption) -> Unit
) {
    val groups by vm.homeGroups.collectAsState()
    val allGroups by vm.groups.collectAsState()
    val scan by vm.scan.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val user by vm.userName.collectAsState()

    val matched = remember(groups) { groups.filter { it.view.matched } }
    val featured = remember(matched) { matched.filter { it.view.backdrop.isNotBlank() }.sortedByOption(SortOption.POPULAR).take(5) }
    val cont = remember(groups) {
        groups.filter { val v = it.view; v.lastPlayedAt > 0 && !v.watched && v.positionMs > 5000 }.sortedByDescending { it.view.lastPlayedAt }
    }
    val trending = remember(matched) { matched.sortedByOption(SortOption.POPULAR).take(60) }
    val topRated = remember(matched) { matched.filter { it.view.rating > 0 }.sortedByOption(SortOption.RATING_HIGH).take(60) }
    val newest = remember(matched) { matched.sortedByOption(SortOption.NEWEST).take(60) }
    val recent = remember(groups) { groups.sortedByOption(SortOption.ADDED) }        // every movie, newest first
    // Movie of the day: the same pick all day long, a new one tomorrow (only titles you have not watched).
    val motd = remember(groups) {
        val pool = groups.filter { it.view.matched && it.view.backdrop.isNotBlank() && it.files.none { f -> f.watched } }.sortedBy { it.view.id }
        if (pool.size < 3) null else {
            val c = Calendar.getInstance()
            pool[((c.get(Calendar.YEAR) * 366L + c.get(Calendar.DAY_OF_YEAR)) * 7919L % pool.size).toInt()]
        }
    }
    val series = remember(groups) { groups.filter { it.isSeries }.sortedByOption(SortOption.ADDED) }
    val unmatched = remember(groups) { groups.filter { !it.view.matched } }
    val unmatchedCount = remember(allGroups) { allGroups.count { !it.view.matched } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(30)
                Spacer(Modifier.width(10.dp))
                Text("NWFLIX", fontWeight = FontWeight.Light, fontSize = 20.sp, letterSpacing = 4.sp, modifier = Modifier.weight(1f))
                var refreshMenu by remember { mutableStateOf(false) }
                val scanning by vm.scan.collectAsState()
                Box {
                    IconButton(onClick = { refreshMenu = true }) {
                        if (scanning.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "Refresh")
                    }
                    DropdownMenu(expanded = refreshMenu, onDismissRequest = { refreshMenu = false }) {
                        DropdownMenuItem(text = { Text("Check for new movies") }, onClick = { refreshMenu = false; vm.rescan(false); vm.say("Checking for new movies…") })
                        DropdownMenuItem(text = { Text("Full rescan (slower)") }, onClick = { refreshMenu = false; vm.rescan(true); vm.say("Full scan started") })
                    }
                }
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
                Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center) {
                    Text(user.firstOrNull()?.uppercase() ?: "N", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (scan.running && (groups.isEmpty() || scan.found > 0)) item {
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

        if (groups.isEmpty()) {
            item {
                if (scan.running) EmptyState(Icons.Default.Movie, "Looking for your movies…", "Large chats can take a minute the first time.")
                else EmptyState(Icons.Default.Movie, "No movies found yet",
                    "NWFlix looks for long videos and big video files in the chats and channels you can access.",
                    action = "Scan again", onAction = { vm.rescan(true) })
            }
        } else {
            if (featured.isNotEmpty()) item { Hero(featured, vm, onOpen) }
            if (motd != null) item { MovieOfTheDay(motd, onOpen) }
            if (cont.isNotEmpty()) item { PosterRow(cont, "Continue Watching", prefs.showTitles, null, onOpen) }
            if (trending.isNotEmpty()) item { PosterRow(trending, "Trending Now", prefs.showTitles, { onSeeAll(SortOption.POPULAR) }, onOpen) }
            if (topRated.isNotEmpty()) item { PosterRow(topRated, "Top Rated", prefs.showTitles, { onSeeAll(SortOption.RATING_HIGH) }, onOpen) }
            if (newest.isNotEmpty()) item { PosterRow(newest, "New Releases", prefs.showTitles, { onSeeAll(SortOption.NEWEST) }, onOpen) }
            if (series.isNotEmpty()) item { PosterRow(series, "TV Series", prefs.showTitles, null, onOpen) }
            item { PosterRow(recent, "Recently Added (${recent.size})", prefs.showTitles, { onSeeAll(SortOption.ADDED) }, onOpen) }
            if (prefs.hideUnmatched && unmatchedCount > 0) item {
                UnmatchedNotice(unmatchedCount) { vm.exploreGenre.value = "Unmatched"; onSeeAll(SortOption.ADDED) }
            } else if (unmatched.isNotEmpty()) item { PosterRow(unmatched, "Needs a match (${unmatched.size})", true, null, onOpen) }
        }
    }
}

@Composable
private fun PosterRow(list: List<MovieGroup>, title: String, showTitles: Boolean, onSeeAll: (() -> Unit)?, onOpen: (String) -> Unit) {
    Column {
        SectionHeader(title, action = if (onSeeAll != null) "See all" else null, onAction = { onSeeAll?.invoke() })
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.id }) { g ->
                Poster(g.view, Modifier.width(112.dp), showTitles, g.badge) { onOpen(g.id) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hero(list: List<MovieGroup>, vm: MainViewModel, onOpen: (String) -> Unit) {
    val pager = rememberPagerState(pageCount = { list.size })
    LaunchedEffect(list.size) {
        while (list.size > 1) { delay(6000); pager.animateScrollToPage((pager.currentPage + 1) % list.size) }
    }
    HorizontalPager(pager, Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(230.dp), pageSpacing = 10.dp) { i ->
        val g = list[i]
        val m = g.view
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).clickable { onOpen(g.id) }) {
            AsyncImage(TmdbClient.img(m.backdrop, imgSize("w780", "w500")), m.displayTitle, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            GradientScrim(Modifier.fillMaxSize(), Color(0xFF010314))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(m.displayTitle, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(m.year.toString(), m.genreList.take(2).joinToString(" • "), fmtRuntime(m.runtime)).filter { it.isNotBlank() && it != "0" }.joinToString("  •  "),
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFBABCD2))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NWButton(onClick = { onOpen(g.id) }, shape = RoundedCornerShape(10.dp)) {
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

@Composable
private fun MovieOfTheDay(g: MovieGroup, onOpen: (String) -> Unit) {
    val m = g.view
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp).height(150.dp).clip(RoundedCornerShape(16.dp)).clickable { onOpen(g.id) }) {
        AsyncImage(TmdbClient.img(m.backdrop, imgSize("w780", "w500")), m.displayTitle, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        GradientScrim(Modifier.fillMaxSize(), Color(0xFF010314))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text("MOVIE OF THE DAY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
            Text(m.displayTitle, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(if (m.displayYear > 0) m.displayYear.toString() else "", m.genreList.take(2).joinToString(" • "), if (m.rating > 0) "★ %.1f".format(m.rating) else "")
                .filter { it.isNotBlank() }.joinToString("  •  "), style = MaterialTheme.typography.bodySmall, color = Color(0xFFBABCD2))
        }
    }
}

@Composable
private fun UnmatchedNotice(count: Int, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$count ${if (count == 1) "file has" else "files have"} no movie details yet. Review them in Explore.",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
            )
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
