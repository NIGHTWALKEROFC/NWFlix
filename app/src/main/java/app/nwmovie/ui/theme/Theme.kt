package app.nwmovie.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import app.nwmovie.data.Prefs

val Accents = listOf(0xFF8B6CFF, 0xFF5B6CFF, 0xFFD25CC8, 0xFFFF9F45, 0xFF4CC38A, 0xFFF0607E).map { Color(it) }

private val Serif = FontFamily.Serif

val NWTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 1.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp)
)

@Composable
fun NWTheme(p: Prefs, content: @Composable () -> Unit) {
    val accent = Accents[p.accent.coerceIn(0, Accents.lastIndex)]
    val light = p.theme == "light"
    val scheme = if (light) lightColorScheme(
        primary = accent, onPrimary = Color.White, background = Color(0xFFF4F5FB), onBackground = Color(0xFF0E1330),
        surface = Color.White, onSurface = Color(0xFF0E1330), surfaceVariant = Color(0xFFE6E8F5),
        onSurfaceVariant = Color(0xFF5B6180), outline = Color(0xFFCBCEE3), secondaryContainer = accent.copy(alpha = 0.18f)
    ) else {
        val bg = if (p.theme == "amoled") Color.Black else Color(0xFF07060F)
        val surf = if (p.theme == "amoled") Color(0xFF0B0A12) else Color(0xFF100E1F)
        darkColorScheme(
            primary = accent, onPrimary = Color.White, background = bg, onBackground = Color(0xFFEDEBFF),
            surface = surf, onSurface = Color(0xFFEDEBFF), surfaceVariant = Color(0xFF1D1A38),
            onSurfaceVariant = Color(0xFFA7A3CC), outline = Color(0xFF302B57), secondaryContainer = accent.copy(alpha = 0.25f)
        )
    }
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * p.fontScale)) {
        MaterialTheme(colorScheme = scheme, typography = NWTypography, content = content)
    }
}
