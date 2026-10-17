package app.nwmovie.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nwmovie.MainViewModel
import app.nwmovie.data.MovieGroup
import app.nwmovie.data.SortOption
import app.nwmovie.data.sortedByOption
import app.nwmovie.ui.components.EmptyState
import app.nwmovie.ui.components.Poster

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExploreScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val groups by vm.groups.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val searchOpen by vm.searchOpen.collectAsState()
    val sort by vm.sort.collectAsState()
    var genre by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("Any") }
    var rating by rememberSaveable { mutableStateOf("Any") }
    var status by rememberSaveable { mutableStateOf("Any") }
    var type by rememberSaveable { mutableStateOf("Any") }
    var language by rememberSaveable { mutableStateOf("Any") }
    var surpriseOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }

    val genres = remember(groups) {
        listOf("All") + groups.flatMap { it.view.genreList }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }
    val languages = remember(groups) { groups.flatMap { it.languages }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val activeFilters = listOf(year, rating, status, type, language).count { it != "Any" }
    val shown = remember(groups, genre, query, year, rating, status, type, language, sort) {
        groups.filter { g ->
            val m = g.view
            (genre == "All" || genre in m.genreList) &&
                (query.isBlank() || m.displayTitle.contains(query, true) || m.cast.contains(query, true) || g.files.any { it.fileName.contains(query, true) }) &&
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

    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(Modifier.statusBarsPadding().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Explore", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    IconButton(onClick = { vm.searchOpen.value = !searchOpen; if (searchOpen) query = "" }) {
                        Icon(if (searchOpen) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                }
                if (searchOpen) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 6.dp), singleLine = true,
                    placeholder = { Text("Search movie, actor or director") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    items(genres) { g -> FilterChip(selected = g == genre, onClick = { genre = g }, label = { Text(g) }) }
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
        if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(Icons.Default.Search, "Nothing matches", "Try another genre, search word or clear the filters.")
        }
        items(shown, key = { it.id }) { g ->
            Poster(g.view, Modifier, prefs.showTitles, g.badge) { onOpen(g.id) }
        }
    }

    if (surpriseOpen) SurpriseDialog(groups, onOpen) { surpriseOpen = false }

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
