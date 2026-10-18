package app.nwmovie.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nwmovie.MainViewModel
import app.nwmovie.data.SortOption
import app.nwmovie.data.sortedByOption
import app.nwmovie.ui.components.EmptyState
import app.nwmovie.ui.components.Poster

/** Every movie and series in your library with this actor, director or creator. */
@Composable
fun PersonScreen(vm: MainViewModel, name: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val groups by vm.groups.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val list = remember(groups, name) {
        groups.filter { g -> g.people.any { it.first.equals(name, true) } }.sortedByOption(SortOption.NEWEST)
    }
    val roles = remember(list, name) {
        list.flatMap { g -> g.people.filter { it.first.equals(name, true) }.map { it.second } }
            .filter { it == "Director" || it == "Creator" }.distinct()
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.padding(start = 4.dp)) {
                Text(name, style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${list.size} title(s) in your library" + if (roles.isNotEmpty()) "  •  ${roles.joinToString(" / ")}" else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (list.isEmpty()) EmptyState(Icons.Default.Person, "Nothing found", "No movie in your library lists this person.", Modifier.padding(top = 48.dp))
        else LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(list, key = { it.id }) { g -> Poster(g.view, Modifier, prefs.showTitles, g.badge) { onOpen(g.id) } }
        }
    }
}
