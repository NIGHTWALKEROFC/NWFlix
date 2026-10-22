package app.nwmovie.ui.screens

import app.nwmovie.ui.theme.NWButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nwmovie.MainViewModel
import app.nwmovie.data.CacheManager
import app.nwmovie.data.K
import app.nwmovie.ui.BrandMark
import app.nwmovie.util.fmtSize

/** Shown once after the first login: explains the cache and asks how long to keep it (English / Malayalam). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(vm: MainViewModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val prefs by vm.prefs.collectAsState()
    var days by remember { mutableIntStateOf(if (CacheManager.isLowStorage(ctx)) 1 else 3) }
    val free = remember { CacheManager.freeBytes(ctx) }
    val low = CacheManager.isLowStorage(ctx)
    val lang = prefs.uiLang

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(40)
            Spacer(Modifier.width(12.dp))
            Text(tr(lang, "Quick setup", "ചെറിയ സെറ്റപ്പ്"), style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(12.dp))
        LangToggle(lang) { vm.set(K.uiLang, it) }
        Spacer(Modifier.height(20.dp))

        Text(tr(lang, "What is “cache”?", "“ക്യാഷ്” എന്നാൽ എന്താണ്?"), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            tr(lang,
                "When you watch a movie, small parts of it are kept on your phone for a while. This is called cache. " +
                    "It makes playing faster and saves internet data, but it takes space. " +
                    "Posters and movie information are different: they are saved permanently (they take very little space) so they never have to be downloaded again, and you can delete them any time in Settings. " +
                    "Your downloaded movies are never deleted by cache cleaning.",
                "ഒരു സിനിമ കാണുമ്പോൾ അതിന്റെ ചെറിയ ഭാഗങ്ങൾ കുറച്ചുനേരം ഫോണിൽ സൂക്ഷിക്കും. ഇതിനെയാണ് ക്യാഷ് എന്ന് പറയുന്നത്. ഇത് പ്ലേ വേഗത്തിലാക്കാനും ഇന്റർനെറ്റ് ലാഭിക്കാനും സഹായിക്കും, പക്ഷേ ഫോണിൽ സ്ഥലം എടുക്കും. " +
                    "പോസ്റ്ററുകളും സിനിമയുടെ വിവരങ്ങളും വേറെയാണ്: അവ സ്ഥിരമായി സൂക്ഷിക്കും (വളരെ കുറച്ച് സ്ഥലം മാത്രം), അതിനാൽ വീണ്ടും ഡൗൺലോഡ് ചെയ്യേണ്ടതില്ല. ആവശ്യമെങ്കിൽ Settings-ൽ നിന്ന് ഡിലീറ്റ് ചെയ്യാം. " +
                    "ഡൗൺലോഡ് ചെയ്ത സിനിമകൾ ക്യാഷ് ക്ലീനിങ് കൊണ്ട് ഒരിക്കലും ഡിലീറ്റ് ആകില്ല."),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Text(tr(lang, "How many days should it be kept?", "എത്ര ദിവസം സൂക്ഷിക്കണം?"), style = MaterialTheme.typography.titleMedium)
        Text(tr(lang, "After these days without opening the app, the movie cache is deleted automatically.",
            "ഇത്ര ദിവസം ആപ്പ് തുറക്കാതിരുന്നാൽ സിനിമയുടെ ക്യാഷ് തനിയെ ഡിലീറ്റ് ആകും."),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 3, 7, 15, 30).forEach { d ->
                FilterChip(selected = days == d, onClick = { days = d },
                    label = { Text(tr(lang, if (d == 1) "1 day" else "$d days", "$d ദിവസം") + if (d == 3) tr(lang, " (recommended)", " (നല്ലത്)") else "") })
            }
        }
        Spacer(Modifier.height(16.dp))
        Surface(shape = RoundedCornerShape(14.dp), color = if (low) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (low) Icons.Default.Warning else Icons.Default.Storage, null)
                Spacer(Modifier.width(12.dp))
                Text(
                    tr(lang, "Free space on your phone: ${fmtSize(free)}.", "നിങ്ങളുടെ ഫോണിൽ ബാക്കിയുള്ള സ്ഥലം: ${fmtSize(free)}.") +
                        if (low) tr(lang, "\nYour storage is low. 1 day is best, and NWFlix will also clear the movie data right after you finish watching.",
                            "\nസ്ഥലം കുറവാണ്. 1 ദിവസം തിരഞ്ഞെടുക്കുന്നതാണ് നല്ലത്. സിനിമ കണ്ടു കഴിഞ്ഞാൽ ഉടൻ തന്നെ ആ ഡാറ്റ NWFlix ഡിലീറ്റ് ചെയ്യും.") else "",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(tr(lang, "You can change this any time in Settings → Storage & Cache.", "ഇത് എപ്പോൾ വേണമെങ്കിലും Settings → Storage & Cache-ൽ മാറ്റാം."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(24.dp))
        NWButton(onClick = { vm.set(K.cacheDays, days); vm.set(K.onboarded, true); onDone() },
            Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text(tr(lang, "Continue", "തുടരുക")) }
    }
}
