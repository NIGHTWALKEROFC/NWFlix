package app.nwmovie.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nwmovie.MainViewModel
import app.nwmovie.data.telegram.Tg
import app.nwmovie.ui.BrandMark
import androidx.compose.ui.platform.LocalContext
import app.nwmovie.data.K

@Composable
fun LoginScreen(vm: MainViewModel) {
    val auth by vm.auth.collectAsState()
    val err by vm.authError.collectAsState()
    val busy by vm.busy.collectAsState()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val expired = remember { app.nwmovie.ServiceLocator.tg.wasReady }
    val ctx = LocalContext.current
    val prefs by vm.prefs.collectAsState()
    var guide by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        BrandMark(64)
        Spacer(Modifier.height(10.dp))
        Text("NWMOVIE", style = MaterialTheme.typography.titleLarge, letterSpacing = 6.sp)
        Spacer(Modifier.height(24.dp))
        Text("Connect with Telegram", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            if (expired) "Your Telegram session ended. Please log in again." else "Log in with your Telegram account to access your movies.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        when (val a = auth) {
            is Tg.Auth.NeedKeys -> Text(
                "This copy of NWMovie isn't set up correctly. Please install the latest version from the developer.",
                color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center
            )
            is Tg.Auth.WaitPhone -> {
                Field("Phone number (with country code)", phone, { phone = it }, KeyboardType.Phone)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.submitPhone(phone) }, enabled = phone.length > 5 && !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else { Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text("Login with Telegram") }
                }
                TextButton(onClick = { guide = true }) { Icon(Icons.Default.HelpOutline, null); Spacer(Modifier.width(6.dp)); Text(tr(prefs.uiLang, "How does login work?", "ലോഗിൻ എങ്ങനെ ചെയ്യും?")) }
            }
            is Tg.Auth.WaitCode -> {
                Text("Enter the code Telegram just sent to your Telegram app (or SMS).", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Field("Login code", code, { code = it }, KeyboardType.Number)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.submitCode(code) }, enabled = code.length >= 4 && !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Verify") }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { if (!openTelegram(ctx)) vm.say("Telegram is not installed on this phone") },
                    Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text(tr(prefs.uiLang, "Open Telegram to see the code", "കോഡ് കാണാൻ Telegram തുറക്കുക"))
                }
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { vm.resendCode() }, enabled = !busy) { Text(tr(prefs.uiLang, "Resend code", "കോഡ് വീണ്ടും അയക്കുക")) }
                    TextButton(onClick = { guide = true }) { Text(tr(prefs.uiLang, "Didn't get the code?", "കോഡ് കിട്ടിയില്ലേ?")) }
                }
            }
            is Tg.Auth.WaitPassword -> {
                Text("Your account has two-step verification. Enter your password.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Field("Password", pass, { pass = it }, KeyboardType.Password, hidden = true)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.submitPassword(pass) }, enabled = pass.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Continue") }
            }
            is Tg.Auth.Unsupported -> Text(a.msg, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            else -> CircularProgressIndicator()
        }

        err?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }

        Spacer(Modifier.height(28.dp))
        Info(Icons.Default.Shield, "Secure & encrypted", "Your session is encrypted on this phone.")
        Info(Icons.Default.Movie, "Only movies", "Documents and other files are filtered out.")
        Info(Icons.Default.VisibilityOff, "Private", "Nothing leaves your device except Telegram and TMDB requests.")
        LoginGuideHost(guide, prefs.uiLang, { vm.set(K.uiLang, it) }) { guide = false }
        Spacer(Modifier.height(12.dp))
        Text("Only use NWMovie with content you are allowed to watch.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LoginGuideHost(show: Boolean, lang: String, onLang: (String) -> Unit, onClose: () -> Unit) {
    if (show) OtpGuideDialog(lang, onLang, onClose)
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType = KeyboardType.Text, hidden: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (hidden) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun Info(icon: ImageVector, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.width(12.dp))
        Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
