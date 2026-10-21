package app.nwmovie.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nwmovie.MainViewModel
import app.nwmovie.data.Countries
import app.nwmovie.data.Country
import app.nwmovie.data.K
import app.nwmovie.data.telegram.Tg
import app.nwmovie.ui.BrandMark
import kotlinx.coroutines.delay

private enum class OtpState { Idle, Checking, Wrong }
private const val OTP_LENGTH = 5

@Composable
fun LoginScreen(vm: MainViewModel) {
    val auth by vm.auth.collectAsState()
    val err by vm.authError.collectAsState()
    val busy by vm.busy.collectAsState()
    var phone by remember { mutableStateOf("") }
    var countryIso by rememberSaveable { mutableStateOf("IN") }
    var pickCountry by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf(OtpState.Idle) }
    var pass by remember { mutableStateOf("") }
    val expired = remember { app.nwmovie.ServiceLocator.tg.wasReady }
    val ctx = LocalContext.current
    val prefs by vm.prefs.collectAsState()
    var guide by remember { mutableStateOf(false) }
    var movieGuide by remember { mutableStateOf(false) }
    var legal by remember { mutableStateOf<String?>(null) }
    val country = remember(countryIso) { Countries.all.firstOrNull { it.iso == countryIso } ?: Countries.india }

    // the code is checked as soon as the last digit is typed
    LaunchedEffect(code) {
        if (code.length == OTP_LENGTH && otp == OtpState.Idle) {
            otp = OtpState.Checking
            vm.clearAuthError()
            vm.submitCode(code)
        }
    }
    // a wrong code: shake, then ask for it again
    LaunchedEffect(busy, err) {
        if (otp == OtpState.Checking && !busy && err != null) {
            otp = OtpState.Wrong
            delay(1100)
            code = ""
            vm.clearAuthError()
            otp = OtpState.Idle
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        BrandMark(64)
        Spacer(Modifier.height(10.dp))
        Text("NWFLIX", style = MaterialTheme.typography.titleLarge, letterSpacing = 6.sp)
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
                "This copy of NWFlix isn't set up correctly. Please install the latest version from the developer.",
                color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center
            )
            is Tg.Auth.WaitPhone -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { pickCountry = true }, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(56.dp), contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(country.flag, fontSize = 20.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("+${country.dial}")
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() || c == ' ' || c == '+' }.take(18) },
                        label = { Text("Phone number") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)
                    )
                }
                Text("${country.name} is selected. Type your number without the country code.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.submitPhone(Countries.fullNumber(country, phone)) }, enabled = phone.count { it.isDigit() } > 5 && !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else { Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text("Login with Telegram") }
                }
                TextButton(onClick = { guide = true }) { Icon(Icons.Default.HelpOutline, null); Spacer(Modifier.width(6.dp)); Text(tr(prefs.uiLang, "How does login work?", "ലോഗിൻ എങ്ങനെ ചെയ്യും?")) }
                TextButton(onClick = { movieGuide = true }) { Icon(Icons.Default.Movie, null); Spacer(Modifier.width(6.dp)); Text(tr(prefs.uiLang, "How do I get movies in NWFlix?", "NWFlix-ൽ സിനിമകൾ എങ്ങനെ കിട്ടും?")) }
            }
            is Tg.Auth.WaitCode -> {
                Text("Enter the code Telegram just sent to your Telegram app (or SMS).", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                OtpBoxes(code, OTP_LENGTH, otp) { code = it }
                Spacer(Modifier.height(10.dp))
                Text(
                    when (otp) {
                        OtpState.Checking -> "Checking the code…"
                        OtpState.Wrong -> "That code is wrong. Please try again."
                        else -> "Type the ${OTP_LENGTH}-digit code"
                    },
                    color = if (otp == OtpState.Wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = { if (!openTelegram(ctx)) vm.say("Telegram is not installed on this phone") },
                    Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text(tr(prefs.uiLang, "Open Telegram to see the code", "കോഡ് കാണാൻ Telegram തുറക്കുക"))
                }
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { vm.resendCode() }, enabled = !busy && otp != OtpState.Checking) { Text(tr(prefs.uiLang, "Resend code", "കോഡ് വീണ്ടും അയക്കുക")) }
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

        // messages that are not about the code are shown here
        if (auth !is Tg.Auth.WaitCode) err?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }

        Spacer(Modifier.height(28.dp))
        Info(Icons.Default.Shield, "Secure & encrypted", "Your session is encrypted on this phone.")
        Info(Icons.Default.Movie, "Only movies", "Documents and other files are filtered out.")
        Info(Icons.Default.VisibilityOff, "Private", "Your chats and movie list stay on your phone. Details: Privacy Policy.")
        Spacer(Modifier.height(12.dp))
        Text("By continuing you agree to the Terms & Conditions and the Privacy Policy.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { legal = "terms" }) { Text("Terms & Conditions") }
            TextButton(onClick = { legal = "privacy" }) { Text("Privacy Policy") }
        }
        Text("Only use NWFlix with content you are allowed to watch.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }

    if (pickCountry) CountryDialog(countryIso, { countryIso = it.iso; pickCountry = false }) { pickCountry = false }
    if (guide) OtpGuideDialog(prefs.uiLang, { vm.set(K.uiLang, it) }) { guide = false }
    if (movieGuide) MoviesGuideDialog(prefs.uiLang, { vm.set(K.uiLang, it) }) { movieGuide = false }
    legal?.let { LegalDialog(it) { legal = null } }
}

/** Code boxes. While the code is being checked the boxes spin; a wrong code turns them red and shakes them. */
@Composable
private fun OtpBoxes(code: String, length: Int, state: OtpState, onCode: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(state) { if (state == OtpState.Idle) try { focus.requestFocus() } catch (_: Exception) { } }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == OtpState.Wrong) {
            repeat(3) { shake.animateTo(16f, tween(55)); shake.animateTo(-16f, tween(55)) }
            shake.animateTo(0f, tween(55))
        }
    }
    val spin = rememberInfiniteTransition(label = "otp-spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    BasicTextField(
        value = code,
        onValueChange = { v -> if (state == OtpState.Idle) onCode(v.filter { it.isDigit() }.take(length)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = TextStyle(color = Color.Transparent),
        modifier = Modifier.focusRequester(focus),
        decorationBox = {
            Row(
                Modifier.graphicsLayer { translationX = shake.value * density },
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(length) { i ->
                    val digit = code.getOrNull(i)?.toString() ?: ""
                    val active = state == OtpState.Idle && i == code.length.coerceAtMost(length - 1)
                    val border = when {
                        state == OtpState.Wrong -> error
                        state == OtpState.Checking -> primary
                        active -> primary
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (state == OtpState.Wrong) error.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(if (active || state != OtpState.Idle) 2.dp else 1.dp, border),
                        modifier = Modifier.size(width = 48.dp, height = 60.dp).graphicsLayer {
                            if (state == OtpState.Checking) {
                                rotationY = angle + i * 40f      // each box turns a little after the one before it
                                cameraDistance = 14f * density
                            }
                        }.clip(RoundedCornerShape(12.dp)).clickable { try { focus.requestFocus() } catch (_: Exception) { } }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(digit, fontSize = 24.sp, color = if (state == OtpState.Wrong) error else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun CountryDialog(selected: String, onPick: (Country) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(q) {
        val t = q.trim().removePrefix("+")
        if (t.isEmpty()) Countries.all else Countries.all.filter { it.name.contains(t, ignoreCase = true) || it.dial.startsWith(t) || it.iso.equals(t, ignoreCase = true) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Select country", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                }
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true, label = { Text("Search country or code") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(list, key = { it.iso }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(c.flag, fontSize = 22.sp)
                            Spacer(Modifier.width(12.dp))
                            Text(c.name, Modifier.weight(1f), color = if (c.iso == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text("+${c.dial}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
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
