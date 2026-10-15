package app.nwmovie.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import app.nwmovie.MainViewModel
import app.nwmovie.data.telegram.Tg
import app.nwmovie.ui.screens.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("explore", "Explore", Icons.Default.Explore),
    Tab("downloads", "Downloads", Icons.Default.Download),
    Tab("mylist", "My List", Icons.Default.Bookmark),
    Tab("settings", "Settings", Icons.Default.Settings)
)

@Composable
fun NWRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val auth by vm.auth.collectAsState()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val snack = remember { SnackbarHostState() }
    val isMain = route in tabs.map { it.route }

    LaunchedEffect(Unit) { vm.toast.collect { snack.showSnackbar(it) } }

    // Telegram needs (re)login at any time (first run, session expired, logout) → login screen.
    LaunchedEffect(auth, route) {
        if (route != null && route != "splash" && route != "login" &&
            auth !is Tg.Auth.Ready && auth !is Tg.Auth.Loading
        ) nav.navigate("login") { popUpTo(0) }
        if (route == "login" && auth is Tg.Auth.Ready) nav.navigate("home") { popUpTo(0) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (isMain) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }
        }
    ) { pad ->
        NavHost(
            navController = nav, startDestination = "splash",
            modifier = Modifier.padding(bottom = if (isMain) pad.calculateBottomPadding() else 0.dp),
            enterTransition = { fadeIn() }, exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() }, popExitTransition = { fadeOut() }
        ) {
            composable("splash") {
                SplashScreen {
                    // wait for Telegram to report its state, then go
                    val a = vm.auth.first { it !is Tg.Auth.Loading }
                    nav.navigate(if (a is Tg.Auth.Ready) "home" else "login") { popUpTo("splash") { inclusive = true } }
                }
            }
            composable("login") { LoginScreen(vm) }
            composable("home") {
                HomeScreen(vm, onOpen = { nav.navigate("detail/$it") },
                    onSearch = { vm.searchOpen.value = true; nav.navigate("explore") },
                    onSettings = { nav.navigate("settings") })
            }
            composable("explore") { ExploreScreen(vm, onOpen = { nav.navigate("detail/$it") }) }
            composable("downloads") { DownloadsScreen(vm, onPlay = { nav.navigate("player/$it") }) }
            composable("mylist") { LibraryScreen(vm, onOpen = { nav.navigate("detail/$it") }) }
            composable("settings") { SettingsScreen(vm, onPage = { nav.navigate("page/$it") }) }
            composable("detail/{id}") { b ->
                DetailScreen(vm, b.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() },
                    onPlay = { nav.navigate("player/$it") })
            }
            composable("player/{id}") { b ->
                PlayerScreen(vm, b.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() },
                    onNext = { next -> nav.navigate("player/$next") { popUpTo("player/{id}") { inclusive = true } } })
            }
            composable("page/{name}") { b -> SettingsPages(vm, b.arguments?.getString("name") ?: "", onBack = { nav.popBackStack() }) }
        }
    }
}

@Composable
fun BrandMark(size: Int = 84) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(size.dp)) {
        drawRoundRect(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f))), size = Size(this.size.width, this.size.height),
            cornerRadius = CornerRadius(this.size.width * 0.26f))
        val w = this.size.width
        val p = Path().apply {
            moveTo(w * 0.38f, w * 0.27f); lineTo(w * 0.38f, w * 0.73f); lineTo(w * 0.76f, w * 0.5f); close()
        }
        drawPath(p, Color.White)
    }
}

@Composable
fun SplashScreen(onReady: suspend () -> Unit) {
    LaunchedEffect(Unit) { delay(900); onReady() }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF070B1A), Color(0xFF1A1540), Color(0xFF070B1A)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(92)
            Spacer(Modifier.height(18.dp))
            Text("NWMOVIE", fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = 8.sp, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("YOUR MOVIES. YOUR SPACE.", fontSize = 11.sp, letterSpacing = 3.sp, color = Color(0xFF9AA0C4))
            Spacer(Modifier.height(36.dp))
            LinearProgressIndicator(Modifier.width(140.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color(0xFF1A2140))
        }
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(72)
            Spacer(Modifier.height(16.dp))
            Text("NWMovie is locked", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onUnlock) { Icon(Icons.Default.Lock, null); Spacer(Modifier.width(8.dp)); Text("Unlock") }
        }
    }
}
