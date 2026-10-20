package app.nwmovie.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Picks the English or Malayalam text. */
fun tr(lang: String, en: String, ml: String): String = if (lang == "ml") ml else en

/** Opens the Telegram app (official, Telegram X or the web build). Returns false when it can't be opened. */
fun openTelegram(ctx: Context): Boolean {
    val pm = ctx.packageManager
    for (pkg in listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")) {
        val i = pm.getLaunchIntentForPackage(pkg)
        if (i != null) { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true }
    }
    return try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
}

@Composable
fun LangToggle(lang: String, onLang: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = lang == "en", onClick = { onLang("en") }, label = { Text("English") })
        FilterChip(selected = lang == "ml", onClick = { onLang("ml") }, label = { Text("മലയാളം") })
    }
}

@Composable
private fun StepCard(number: String, icon: ImageVector, title: String, body: String, extra: @Composable (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Text(number, color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.width(12.dp))
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (extra != null) { Spacer(Modifier.height(10.dp)); extra() }
        }
    }
}

/** A small drawing of what the Telegram login message looks like. */
@Composable
private fun FakeTelegramChat(lang: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF17212B)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Color(0xFF2AABEE), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Send, null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Telegram", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Verified, null, tint = Color(0xFF2AABEE), modifier = Modifier.size(16.dp))
                }
                Text("Login code: 12345. " + tr(lang, "Do not give this code to anyone…", "ഈ കോഡ് ആർക്കും നൽകരുത്…"),
                    color = Color(0xFF9BA7B4), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun OtpGuideDialog(lang: String, onLang: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(lang, "How to get the login code", "ലോഗിൻ കോഡ് എങ്ങനെ കിട്ടും"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                }
                LangToggle(lang, onLang)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    Text(tr(lang, "Where to find the code", "കോഡ് എവിടെ കാണാം"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    StepCard("1", Icons.Default.PhoneAndroid, tr(lang, "Open Telegram", "Telegram തുറക്കുക"),
                        tr(lang, "Open the Telegram app on this phone. If you are logged in to Telegram on another phone, look there.",
                            "ഈ ഫോണിലെ Telegram ആപ്പ് തുറക്കുക. മറ്റൊരു ഫോണിൽ Telegram ലോഗിൻ ആണെങ്കിൽ അതിൽ നോക്കുക."))
                    StepCard("2", Icons.Default.Chat, tr(lang, "Find the chat named “Telegram”", "“Telegram” എന്ന ചാറ്റ് നോക്കുക"),
                        tr(lang, "In your chat list, open the chat called Telegram (blue tick). The newest message has your code.",
                            "ചാറ്റ് ലിസ്റ്റിൽ നീല ടിക്ക് ഉള്ള “Telegram” എന്ന ചാറ്റ് തുറക്കുക. ഏറ്റവും പുതിയ സന്ദേശത്തിൽ കോഡ് ഉണ്ടാകും.")) { FakeTelegramChat(lang) }
                    StepCard("3", Icons.Default.Pin, tr(lang, "Type the 5-digit code in NWFlix", "5 അക്ക കോഡ് NWFlix-യിൽ നൽകുക"),
                        tr(lang, "Come back to NWFlix and enter the code. Never share this code with anyone.",
                            "NWFlix-യിലേക്ക് തിരിച്ചുവന്ന് കോഡ് നൽകുക. ഈ കോഡ് ആർക്കും പങ്കുവെക്കരുത്."))

                    Spacer(Modifier.height(10.dp))
                    Text(tr(lang, "Code not coming?", "കോഡ് വരുന്നില്ലേ?"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    StepCard("A", Icons.Default.Sms, tr(lang, "Not logged in anywhere? Check SMS", "ഒരിടത്തും ലോഗിൻ അല്ലേ? SMS നോക്കുക"),
                        tr(lang, "If Telegram is not logged in on any device, the code comes by SMS. It can take 1-2 minutes. Check your SMS app and spam folder.",
                            "Telegram ഒരു ഉപകരണത്തിലും ലോഗിൻ അല്ലെങ്കിൽ കോഡ് SMS ആയി വരും. 1-2 മിനിറ്റ് എടുക്കാം. SMS ആപ്പും സ്പാം ഫോൾഡറും നോക്കുക."))
                    StepCard("B", Icons.Default.Refresh, tr(lang, "Tap “Resend code”", "“Resend code” അമർത്തുക"),
                        tr(lang, "Wait a minute, then tap Resend code. Telegram can send the code by SMS or by a phone call.",
                            "ഒരു മിനിറ്റ് കാത്തിരുന്ന് Resend code അമർത്തുക. Telegram SMS ആയോ ഫോൺ കോൾ ആയോ കോഡ് അയക്കും."))
                    StepCard("C", Icons.Default.Edit, tr(lang, "Check the phone number", "ഫോൺ നമ്പർ പരിശോധിക്കുക"),
                        tr(lang, "Use the country code, for example +91 98765 43210. A wrong number never gets a code.",
                            "രാജ്യ കോഡോടെ നൽകുക, ഉദാഹരണം +91 98765 43210. നമ്പർ തെറ്റാണെങ്കിൽ കോഡ് വരില്ല."))
                    StepCard("D", Icons.Default.HourglassTop, tr(lang, "Too many tries?", "ഒരുപാട് തവണ ശ്രമിച്ചോ?"),
                        tr(lang, "Telegram blocks login for a few hours after many attempts. Wait, then try once more.",
                            "ഒരുപാട് ശ്രമിച്ചാൽ Telegram കുറച്ച് മണിക്കൂർ ലോഗിൻ തടയും. കാത്തിരുന്ന് വീണ്ടും ശ്രമിക്കുക."))
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { if (!openTelegram(ctx)) android.widget.Toast.makeText(ctx, tr(lang, "Telegram is not installed", "Telegram ഇൻസ്റ്റാൾ ചെയ്തിട്ടില്ല"), android.widget.Toast.LENGTH_SHORT).show() },
                    Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text(tr(lang, "Open Telegram", "Telegram തുറക്കുക"))
                }
            }
        }
    }
}
