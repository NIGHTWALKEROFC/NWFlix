package app.nwmovie.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.nwmovie.MainViewModel
import app.nwmovie.data.MovieGroup
import app.nwmovie.data.SortOption
import app.nwmovie.data.sortedByOption
import app.nwmovie.ui.components.EmptyState
import app.nwmovie.ui.components.Poster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExploreScreen(vm: MainViewModel, onOpen: (String) -> Unit, onPerson: (String) -> Unit = {}) {
    val groups by vm.groups.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val searchOpen by vm.searchOpen.collectAsState()
    val sort by vm.sort.collectAsState()
    val scan by vm.scan.collectAsState()
    val pendingGenre by vm.exploreGenre.collectAsState()
    var genre by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("Any") }
    var rating by rememberSaveable { mutableStateOf("Any") }
    var status by rememberSaveable { mutableStateOf("Any") }
    var type by rememberSaveable { mutableStateOf("Any") }
    var language by rememberSaveable { mutableStateOf("Any") }
    var sortOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var surpriseOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(pendingGenre) { pendingGenre?.let { genre = it; vm.exploreGenre.value = null } }
    val unmatchedCount = remember(groups) { groups.count { !it.view.matched } }

    val genres = remember(groups) {
        listOf("All") + (if (unmatchedCount > 0) listOf("Unmatched") else emptyList()) + groups.flatMap { it.view.genreList }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }
    val languages = remember(groups) { groups.flatMap { it.languages }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val people = remember(groups) { groups.flatMap { g -> g.people.map { it.first } }.distinct() }
    val activeFilters = listOf(year, rating, status, type, language).count { it != "Any" }

    // The filtering/sorting runs on a background thread so the screen never freezes, even with thousands of files.
    var shown by remember { mutableStateOf(emptyList<MovieGroup>()) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(groups, genre, query, year, rating, status, type, language, sort, prefs.hideUnmatched) {
        val q = query.trim().lowercase()
        shown = withContext(Dispatchers.Default) {
            groups.filter { g ->
                val m = g.view
                (if (genre == "Unmatched") !m.matched
                else (m.matched || !prefs.hideUnmatched || q.isNotEmpty() || m.lastPlayedAt > 0 || m.watchlist || m.favorite) &&
                    (genre == "All" || genre in m.genreList)) &&
                    (q.isEmpty() || g.searchText.contains(q)) &&
                    (type == "Any" || (type == "Series") == g.isSeries) &&
                    (language == "Any" || language in g.languages) &&
                    when (year) {
                        "2020s" -> m.displayYear in 2020..2029; "2010s" -> m.displayYear in 2010..2019
                        "2000s" -> m.displayYear in 2000..2009; "1990s" -> m.displayYear in 1990..1999
                        "Older" -> m.displayYear in 1..1989; else -> true
                    } &&
                    when (rating) { "6+" -> m.rating >= 6; "7+" -> m.rating >= 7; "8+" -> m.rating >= 8; else -> true } &&
                    when (status) {
                        "Unwatched" -> m.lastPlayedAt == 0L
                        "In progress" -> m.positionMs > 5000 && !m.watched
                        "Watched" -> m.watched
                        else -> true
                    }
            }.sortedByOption(sort)
        }
        ready = true
    }

    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(Modifier.statusBarsPadding().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Explore", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    IconButton(onClick = { vm.searchOpen.value = !searchOpen; if (searchOpen) { query = ""; focus.clearFocus() } }) {
                        Icon(if (searchOpen) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                }
                if (searchOpen) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 6.dp).onFocusChanged { focused = it.isFocused }, singleLine = true,
                        placeholder = { Text("Search movie, actor or director") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.addSearch(query); focus.clearFocus() }))
                    if (focused) Suggestions(
                        query = query, history = prefs.searchHistory.split("\n").filter { it.isNotBlank() }, groups = groups, people = people, genres = genres,
                        onPickText = { query = it; vm.addSearch(it); focus.clearFocus() },
                        onOpenTitle = { g -> vm.addSearch(g.view.displayTitle); focus.clearFocus(); onOpen(g.id) },
                        onPerson = { n -> vm.addSearch(n); focus.clearFocus(); onPerson(n) },
                        onGenre = { g -> genre = g; query = ""; focus.clearFocus() },
                        onClearHistory = { vm.clearSearchHistory() }
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    items(genres) { g -> FilterChip(selected = g == genre, onClick = { genre = g }, label = { Text(if (g == "Unmatched") "Unmatched · $unmatchedCount" else g) }) }
                }
                Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { sortOpen = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Icon(Icons.Default.SwapVert, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(sort.short)
                    }
                    OutlinedButton(onClick = { filterOpen = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Icon(Icons.Default.FilterList, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (activeFilters > 0) "Filters · $activeFilters" else "Filters")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { surpriseOpen = true }) { Icon(Icons.Default.Shuffle, "Surprise me") }
                    Text("${shown.size} titles", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (genre == "Unmatched") item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "These files have no movie details yet. Open one and use “Wrong or missing match?” to pick the right movie, or let the app try again.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    if (scan.running) Text(scan.phase.ifBlank { "Working…" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else OutlinedButton(onClick = { vm.rescan(true) }) { Text("Try matching again") }
                }
            }
        }
        if (!ready) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(Icons.Default.Search, "Nothing matches", "Try another genre, search word or clear the filters.")
        }
        items(shown, key = { it.id }) { g ->
            Poster(g.view, Modifier, prefs.showTitles, g.badge) { if (query.length >= 2) vm.addSearch(query); onOpen(g.id) }
        }
    }

    if (surpriseOpen) SurpriseDialog(groups.filter { it.view.matched }, onOpen) { surpriseOpen = false }

    if (sortOpen) ModalBottomSheet(onDismissRequest = { sortOpen = false }) {
        Text("Sort by", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        SortOption.values().forEach { o ->
            Row(Modifier.fillMaxWidth().clickable { vm.sort.value = o; sortOpen = false }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(o.label, Modifier.weight(1f), color = if (o == sort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (o == sort) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(24.dp).navigationBarsPadding())
    }

    if (filterOpen) ModalBottomSheet(onDismissRequest = { filterOpen = false }) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
            ChipGroup("Release year", listOf("Any", "2020s", "2010s", "2000s", "1990s", "Older"), year) { year = it }
            ChipGroup("Minimum rating", listOf("Any", "6+", "7+", "8+"), rating) { rating = it }
            ChipGroup("Watch status", listOf("Any", "Unwatched", "In progress", "Watched"), status) { status = it }
            ChipGroup("Type", listOf("Any", "Movies", "Series"), type) { type = it }
            if (languages.isNotEmpty()) ChipGroup("Language (from file names)", listOf("Any") + languages, language) { language = it }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { year = "Any"; rating = "Any"; status = "Any"; type = "Any"; language = "Any"; genre = "All" }, Modifier.weight(1f)) { Text("Clear all") }
                Button(onClick = { filterOpen = false }, Modifier.weight(1f)) { Text("Show ${shown.size}") }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** Recent searches while the box is empty; matching titles, people and genres while typing. */
@Composable
private fun Suggestions(
    query: String, history: List<String>, groups: List<MovieGroup>, people: List<String>, genres: List<String>,
    onPickText: (String) -> Unit, onOpenTitle: (MovieGroup) -> Unit, onPerson: (String) -> Unit, onGenre: (String) -> Unit, onClearHistory: () -> Unit
) {
    val q = query.trim()
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            if (q.isEmpty()) {
                if (history.isEmpty()) Text("Type a movie, actor, director or genre", Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = onClearHistory) { Text("Clear") }
                    }
                    history.forEach { h -> SuggestRow(Icons.Default.History, h) { onPickText(h) } }
                }
            } else {
                val titles = remember(q, groups) { groups.filter { it.view.displayTitle.contains(q, true) }.take(4) }
                val ppl = remember(q, people) { people.filter { it.contains(q, true) }.take(4) }
                val gen = remember(q, genres) { genres.filter { it != "All" && it != "Unmatched" && it.contains(q, true) }.take(2) }
                titles.forEach { g -> SuggestRow(Icons.Default.Movie, g.view.displayTitle + if (g.view.displayYear > 0) "  (${g.view.displayYear})" else "") { onOpenTitle(g) } }
                ppl.forEach { n -> SuggestRow(Icons.Default.Person, n) { onPerson(n) } }
                gen.forEach { n -> SuggestRow(Icons.Default.Category, "Genre: $n") { onGenre(n) } }
                if (titles.isEmpty() && ppl.isEmpty() && gen.isEmpty()) Text("No suggestions. Press search to look in file names.", Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SuggestRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(title: String, options: List<String>, selected: String, onPick: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(o) }) }
    }
}

private val moods = linkedMapOf(
    "Anything" to emptySet<String>(),
    "Laugh" to setOf("Comedy"),
    "Edge of the seat" to setOf("Thriller", "Action", "Crime"),
    "Romantic / emotional" to setOf("Romance", "Drama"),
    "Mind-bending" to setOf("Science Fiction", "Mystery", "Fantasy"),
    "Family time" to setOf("Family", "Animation", "Adventure"),
    "Scary" to setOf("Horror")
)

/** Surprise me: a random movie or series you have not watched yet, matching a mood. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SurpriseDialog(groups: List<MovieGroup>, onOpen: (String) -> Unit, onClose: () -> Unit) {
    var mood by remember { mutableStateOf("Anything") }
    var pick by remember { mutableStateOf<MovieGroup?>(null) }
    var none by remember { mutableStateOf(false) }
    fun roll() {
        val wanted = moods[mood] ?: emptySet()
        val pool = groups.filter { g -> g.view.lastPlayedAt == 0L && !g.files.any { it.watched } && (wanted.isEmpty() || g.view.genreList.any { it in wanted }) }
        pick = pool.randomOrNull(); none = pick == null
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Surprise me") },
        text = {
            Column {
                Text("What are you in the mood for?", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    moods.keys.forEach { k -> FilterChip(selected = mood == k, onClick = { mood = k; pick = null; none = false }, label = { Text(k) }) }
                }
                pick?.let { g ->
                    Spacer(Modifier.height(14.dp))
                    Text(g.view.displayTitle, style = MaterialTheme.typography.titleLarge)
                    Text(listOf(if (g.view.displayYear > 0) g.view.displayYear.toString() else "", g.view.genreList.take(2).joinToString(" • "),
                        if (g.view.rating > 0) "★ %.1f".format(g.view.rating) else "").filter { it.isNotBlank() }.joinToString("  •  "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (g.view.overview.isNotBlank()) Text(g.view.overview, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                if (none) Text("Nothing unwatched matches this mood.", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (pick != null) TextButton(onClick = { onOpen(pick!!.id); onClose() }) { Text("Open") }
            else TextButton(onClick = { roll() }) { Text("Pick for me") }
        },
        dismissButton = {
            Row {
                if (pick != null) TextButton(onClick = { roll() }) { Text("Another") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    )
}
