package app.nwmovie.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nwmovie.data.Prefs

/** Colour tokens of the NWFlix design (dark only). */
object NW {
    val VoidCanvas = Color(0xFF010314)
    val ObsidianSurface = Color(0xFF171825)
    val SlateLayer = Color(0xFF2A2B3A)
    val CarbonBorder = Color(0xFF343543)
    val AshText = Color(0xFF5E6077)
    val FogText = Color(0xFF9FA2B9)
    val MistText = Color(0xFFBABCD2)
    val LunarWhite = Color(0xFFDFE1F4)
    val FrostWhite = Color(0xFFECECFB)
    val UltravioletKey = Color(0xFFB88CFF)
    val DeepIris = Color(0xFF6633EE)
    val AmethystGlow = Color(0xFF8E64FF)
    val ShadowIris = Color(0xFF2E2A4C)
    val PlumDepth = Color(0xFF1F1D3A)
    val CodeRose = Color(0xFFF92672)
}

/** Accent choices in Settings → Appearance. The first one is the brand violet. */
val Accents = listOf(NW.UltravioletKey, NW.AmethystGlow, Color(0xFFE08CFF), Color(0xFFFFB86B), Color(0xFF6FE3B0), NW.CodeRose)

private val Sans = FontFamily.SansSerif

// Headlines: tight negative tracking. Body and UI: comfortable line height.
val NWTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 31.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.18).sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 11.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp)
)

private val NWShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),     // badges
    small = RoundedCornerShape(12.dp),         // inputs
    medium = RoundedCornerShape(16.dp),        // cards
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun NWTheme(p: Prefs, content: @Composable () -> Unit) {
    val accent = Accents[p.accent.coerceIn(0, Accents.lastIndex)]
    val canvas = if (p.theme == "amoled") Color.Black else NW.VoidCanvas
    val scheme = darkColorScheme(
        primary = accent, onPrimary = NW.VoidCanvas,
        primaryContainer = NW.PlumDepth, onPrimaryContainer = NW.FrostWhite,
        secondary = NW.AmethystGlow, onSecondary = Color.White,
        secondaryContainer = NW.ShadowIris, onSecondaryContainer = NW.FrostWhite,
        tertiary = NW.DeepIris, onTertiary = Color.White,
        background = canvas, onBackground = NW.LunarWhite,
        surface = NW.ObsidianSurface, onSurface = NW.LunarWhite,
        surfaceVariant = NW.SlateLayer, onSurfaceVariant = NW.FogText,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = canvas, surfaceContainerLow = Color(0xFF0C0D1D), surfaceContainer = NW.ObsidianSurface,
        surfaceContainerHigh = Color(0xFF202131), surfaceContainerHighest = NW.SlateLayer,
        outline = NW.CarbonBorder, outlineVariant = NW.ShadowIris,
        error = NW.CodeRose, onError = Color.White
    )
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * p.fontScale)) {
        MaterialTheme(colorScheme = scheme, typography = NWTypography, shapes = NWShapes, content = content)
    }
}

/** Main button: white pill with dark text. The shape argument is only there so existing calls keep compiling. */
@Composable
fun NWButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = Button(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = CircleShape,
    colors = colors ?: ButtonDefaults.buttonColors(
        containerColor = Color.White, contentColor = NW.VoidCanvas,
        disabledContainerColor = NW.SlateLayer, disabledContentColor = NW.AshText
    ),
    elevation = elevation, border = border, contentPadding = contentPadding,
    interactionSource = interactionSource, content = content
)

/** Secondary button: transparent pill with a thin border. */
@Composable
fun NWOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = OutlinedButton(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = CircleShape,
    colors = colors ?: ButtonDefaults.outlinedButtonColors(contentColor = NW.LunarWhite, disabledContentColor = NW.AshText),
    elevation = elevation, border = border ?: BorderStroke(1.dp, NW.CarbonBorder), contentPadding = contentPadding,
    interactionSource = interactionSource, content = content
)
