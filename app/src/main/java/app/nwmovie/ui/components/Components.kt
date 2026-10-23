package app.nwmovie.ui.components

import app.nwmovie.ui.theme.NWButton
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nwmovie.ServiceLocator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.nwmovie.data.db.Movie
import app.nwmovie.data.tmdb.TmdbClient
import coil.compose.AsyncImage
import java.util.Locale

/** Picture size to request: the small one while Data saver is on. */
@Composable
fun imgSize(normal: String, saver: String): String {
    val p by ServiceLocator.prefs.collectAsState()
    return if (p.bandwidthSaver) saver else normal
}

@Composable
fun Poster(movie: Movie, modifier: Modifier = Modifier, showTitle: Boolean = true, badge: String? = null, fresh: String? = null, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val url = TmdbClient.img(movie.poster, imgSize("w342", "w185"))
            if (url != null) {
                AsyncImage(model = url, contentDescription = movie.displayTitle, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(movie.displayTitle, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (movie.rating > 0) RatingChip(movie.rating, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            if (badge != null) Text(badge, style = MaterialTheme.typography.bodySmall, color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)).padding(horizontal = 6.dp, vertical = 2.dp))
            if (fresh != null) Text(fresh, style = MaterialTheme.typography.labelSmall, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFE50914)).padding(horizontal = 6.dp, vertical = 2.dp))
            if (movie.positionMs > 0 && movie.durationMs > 0 && !movie.watched) {
                LinearProgressIndicator(
                    progress = { (movie.positionMs.toFloat() / movie.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary, trackColor = Color.Black.copy(alpha = 0.5f)
                )
            }
        }
        if (showTitle) {
            Text(movie.displayTitle, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
            if (movie.displayYear > 0) Text(movie.displayYear.toString(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Netflix-style "Top 10" card: a big number partly hidden behind the poster. */
@Composable
fun RankedPoster(rank: Int, movie: Movie, fresh: String? = null, onClick: () -> Unit) {
    Row(Modifier.height(168.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            rank.toString(),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 96.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, letterSpacing = (-8).sp,
                color = Color.White.copy(alpha = 0.92f),
                drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f)
            ),
            modifier = Modifier.offset(x = 16.dp).padding(bottom = 4.dp), maxLines = 1, softWrap = false
        )
        Poster(movie, Modifier.width(112.dp), false, null, fresh, onClick)
    }
}

@Composable
fun RatingChip(rating: Double, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Star, null, tint = Color(0xFFFFC857), modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(2.dp))
        Text(String.format(Locale.US, "%.1f", rating), style = MaterialTheme.typography.bodySmall, color = Color.White)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); NWButton(onClick = onAction) { Text(action) } }
    }
}

@Composable
fun GradientScrim(modifier: Modifier = Modifier, bottomColor: Color = MaterialTheme.colorScheme.background) {
    Box(modifier.background(Brush.verticalGradient(listOf(Color.Transparent, bottomColor.copy(alpha = 0.6f), bottomColor))))
}

@Composable
fun StorageBar(used: Long, total: Long, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val frac = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(progress = { frac }, modifier = modifier.fillMaxWidth().height(height).clip(CircleShape),
        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
}
