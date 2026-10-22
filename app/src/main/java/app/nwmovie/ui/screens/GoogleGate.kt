package app.nwmovie.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nwmovie.MainViewModel
import app.nwmovie.data.GoogleAuth
import app.nwmovie.data.Telemetry
import app.nwmovie.ui.BrandMark
import app.nwmovie.ui.theme.NWButton
import app.nwmovie.util.findActivity
import kotlinx.coroutines.launch

/** Full-screen "Continue with Google" step. Signing in is required to use the app. */
@Composable
fun GoogleGate(vm: MainViewModel) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val prefs by vm.prefs.collectAsState()
    val lang = prefs.uiLang
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var legal by remember { mutableStateOf<String?>(null) }

    BackHandler(true) { }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clickable(enabled = false) { }) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            BrandMark(72)
            Spacer(Modifier.height(20.dp))
            Text(tr(lang, "Sign in with Google", "Google ഉപയോഗിച്ച് സൈൻ ഇൻ ചെയ്യുക"), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                tr(lang,
                    "Pick your Google account with one tap. No password. It is used to count real users and to reach you with important notices.",
                    "ഒറ്റ ടാപ്പിൽ നിങ്ങളുടെ Google അക്കൗണ്ട് തിരഞ്ഞെടുക്കുക. പാസ്‌വേഡ് വേണ്ട. യഥാർത്ഥ ഉപയോക്താക്കളെ എണ്ണാനും പ്രധാന അറിയിപ്പുകൾ നൽകാനും മാത്രമാണ് ഇത് ഉപയോഗിക്കുന്നത്."),
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            NWButton(
                onClick = {
                    val act = activity ?: return@NWButton
                    scope.launch {
                        busy = true; error = null
                        try {
                            val account = GoogleAuth.pick(act)
                            Telemetry.signInWithGoogle(account.idToken)
                        } catch (e: GoogleAuth.Failed) {
                            if (!e.cancelled) error = e.userMessage
                        } catch (e: Exception) {
                            error = tr(lang, "Could not sign in. Check your internet and try again.", "സൈൻ ഇൻ ചെയ്യാനായില്ല. ഇന്റർനെറ്റ് പരിശോധിച്ച് വീണ്ടും ശ്രമിക്കുക.")
                        }
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.background)
                else { Icon(Icons.Default.AccountCircle, null); Spacer(Modifier.width(8.dp)); Text(tr(lang, "Continue with Google", "Google ഉപയോഗിച്ച് തുടരുക")) }
            }
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            Text(tr(lang, "Signing in is required to use NWFlix.", "NWFlix ഉപയോഗിക്കാൻ സൈൻ ഇൻ ആവശ്യമാണ്."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { legal = "privacy" }) { Text("Privacy Policy") }
                TextButton(onClick = { legal = "terms" }) { Text("Terms & Conditions") }
            }
        }
    }
    legal?.let { LegalDialog(it) { legal = null } }
}
