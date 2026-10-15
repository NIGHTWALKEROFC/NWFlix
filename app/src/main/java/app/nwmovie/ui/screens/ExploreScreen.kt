package app.nwmovie.ui.screens

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
import app.nwmovie.data.db.Movie
import app.nwmovie.ui.components.EmptyState
import app.nwmovie.ui.components.Poster

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val movies by vm.movies.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val searchOpen by vm.searchOpen.collectAsState()
    var genre by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("Any year") }
    var rating by rememberSaveable { mutableStateOf("Any rating") }
    var sort by rememberSaveable { mutableStateOf("Popular") }

    val genres = remember(movies) { listOf("All") + movies.flatMap { it.genreList }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val shown = remember(movies, genre, query, year, rating, sort) {
        movies.filter { m ->
            (genre == "All" || genre in m.genreList) &&
                (query.isBlank() || m.displayTitle.contains(query, true) || m.fileName.contains(query, true)) &&
                when (year) {
                    "2020s" -> m.displayYear in 2020..2029; "2010s" -> m.displayYear in 2010..2019
                    "2000s" -> m.displayYear in 2000..2009; "1990s" -> m.displayYear in 1990..1999
                    "Older" -> m.displayYear in 1..1989; else -> true
                } &&
                when (rating) { "6+" -> m.rating >= 6; "7+" -> m.rating >= 7; "8+" -> m.rating >= 8; else -> true }
        }.let { l ->
            when (sort) {
                "Rating" -> l.sortedByDescending { it.rating }
                "Newest" -> l.sortedByDescending { it.displayYear }
                "A–Z" -> l.sortedBy { it.displayTitle.lowercase() }
                "Recently added" -> l.sortedByDescending { it.addedAt }
                else -> l.sortedByDescending { it.popularity }
            }
        }
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
                    placeholder = { Text("Search your movies") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    items(genres) { g -> FilterChip(selected = g == genre, onClick = { genre = g }, label = { Text(g) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    Dropdown(year, listOf("Any year", "2020s", "2010s", "2000s", "1990s", "Older")) { year = it }
                    Dropdown(rating, listOf("Any rating", "6+", "7+", "8+")) { rating = it }
                    Dropdown(sort, listOf("Popular", "Rating", "Newest", "A–Z", "Recently added")) { sort = it }
                }
            }
        }
        if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(Icons.Default.Search, "Nothing matches", "Try another genre, year or search word.")
        }
        items(shown, key = { it.id }) { m: Movie -> Poster(m, Modifier, prefs.showTitles) { onOpen(m.id) } }
    }
}

@Composable
private fun Dropdown(value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(value) }, trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) })
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false }) }
        }
    }
}
