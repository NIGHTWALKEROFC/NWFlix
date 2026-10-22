package app.nwmovie.ui

import app.nwmovie.ui.theme.NWButton
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import app.nwmovie.MainViewModel
import app.nwmovie.R
import app.nwmovie.data.Telemetry
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
    val prefs by vm.prefs.collectAsState()

    LaunchedEffect(Unit) { vm.toast.collect { snack.showSnackbar(it) } }

    // One way to move between the main screens, whatever is on the back stack (profile button, search, "see all"…).
    fun goTab(r: String) {
        if (r == "home") {
            if (!nav.popBackStack("home", false)) nav.navigate("home") { popUpTo(0); launchSingleTop = true }
        } else nav.navigate(r) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true }
    }

    // Telegram needs (re)login at any time (first run, session expired, logout) → login screen.
    LaunchedEffect(auth, route) {
        if (route != null && route != "splash" && route != "login" &&
            auth !is Tg.Auth.Ready && auth !is Tg.Auth.Loading
        ) nav.navigate("login") { popUpTo(0) }
        if (route == "login" && auth is Tg.Auth.Ready) nav.navigate(if (prefs.onboarded) "home" else "setup") { popUpTo(0) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (isMain) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { goTab(t.route) },
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
                    val next = if (a !is Tg.Auth.Ready) "login" else if (vm.prefs.value.onboarded) "home" else "setup"
                    nav.navigate(next) { popUpTo("splash") { inclusive = true } }
                }
            }
            composable("login") { LoginScreen(vm) }
            composable("setup") { SetupScreen(vm, onDone = { nav.navigate("home") { popUpTo(0) } }) }
            composable("home") {
                HomeScreen(vm, onOpen = { nav.navigate("detail/$it") },
                    onSearch = { vm.searchOpen.value = true; goTab("explore") },
                    onSettings = { goTab("settings") },
                    onSeeAll = { s ->
                        vm.sort.value = s
                        goTab("explore")
                    })
            }
            composable("explore") { ExploreScreen(vm, onOpen = { nav.navigate("detail/$it") }, onPerson = { nav.navigate("person/${android.net.Uri.encode(it)}") }) }
            composable("downloads") { DownloadsScreen(vm, onPlay = { nav.navigate("player/$it") }, onFreeSpace = { nav.navigate("page/space") }) }
            composable("mylist") { LibraryScreen(vm, onOpen = { nav.navigate("detail/$it") }) }
            composable("settings") { SettingsScreen(vm, onPage = { nav.navigate("page/$it") }) }
            composable("detail/{id}") { b ->
                DetailScreen(vm, b.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() },
                    onPlay = { nav.navigate("player/$it") },
                    onPerson = { nav.navigate("person/${android.net.Uri.encode(it)}") })
            }
            composable("person/{name}") { b ->
                PersonScreen(vm, b.arguments?.getString("name") ?: "", onBack = { nav.popBackStack() }, onOpen = { nav.navigate("detail/$it") })
            }
            composable("player/{id}") { b ->
                PlayerScreen(vm, b.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() },
                    onNext = { next -> nav.navigate("player/$next") { popUpTo("player/{id}") { inclusive = true } } },
                    onVlc = { vid -> nav.navigate("vlc/$vid") { popUpTo("player/{id}") { inclusive = true } } })
            }
            composable("vlc/{id}") { b ->
                VlcPlayerScreen(vm, b.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() },
                    onNext = { next -> nav.navigate("player/$next") { popUpTo("vlc/{id}") { inclusive = true } } })
            }
            composable("page/{name}") { b -> SettingsPages(vm, b.arguments?.getString("name") ?: "", onBack = { nav.popBackStack() }, onPage = { nav.navigate("page/$it") }) }
        }
    }
    val signedIn by Telemetry.signedInEmail.collectAsState()
    if (Telemetry.googleReady && auth is Tg.Auth.Ready && prefs.onboarded && signedIn.isBlank()) {
        GoogleGate(vm)
    }
    UpdateHost()
    RemoteHost(vm)
}

@Composable
fun BrandMark(size: Int = 84) {
    Image(
        painterResource(R.drawable.nwflix_mark), "NWFlix",
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.24f).dp))
    )
}

@Composable
fun SplashScreen(onReady: suspend () -> Unit) {
    LaunchedEffect(Unit) { delay(900); onReady() }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF010314), Color(0xFF1F1D3A), Color(0xFF010314)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(92)
            Spacer(Modifier.height(18.dp))
            Text("NWFLIX", fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = 8.sp, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("STREAM  ·  ENJOY  ·  ANYTIME", fontSize = 11.sp, letterSpacing = 3.sp, color = Color(0xFF9FA2B9))
            Spacer(Modifier.height(36.dp))
            LinearProgressIndicator(Modifier.width(140.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color(0xFF2A2B3A))
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
            Text("NWFlix is locked", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            NWButton(onClick = onUnlock) { Icon(Icons.Default.Lock, null); Spacer(Modifier.width(8.dp)); Text("Unlock") }
        }
    }
}
