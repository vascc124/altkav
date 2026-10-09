package uk.noammm.kav.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// AltKav+: YOU follows the phone (wallpaper colours on Android 12+, day and night with the system); DUSK is a
// navy night look with an amber accent.
enum class Look { DARK, OLED, LIGHT, YOU, DUSK }

object K {
    var look by mutableStateOf(Look.DARK)
    // Whether the palette in use is a light one (YOU follows the system, so it isn't fixed by the look).
    var light by mutableStateOf(false)
    // True while Battery Saver has put the app in OLED black over the chosen look.
    var saving by mutableStateOf(false)
    var liquid by mutableStateOf(false)

    var bg by mutableStateOf(Color(0xFF101012))
    var surface1 by mutableStateOf(Color(0xFF202023))
    var surface2 by mutableStateOf(Color(0xFF2B2B30))
    var surface3 by mutableStateOf(Color(0xFF343439))
    var surface4 by mutableStateOf(Color(0xFF44444A))
    var border by mutableStateOf(Color(0xFF343439))
    var borderStrong by mutableStateOf(Color(0xFF74747D))
    var dim by mutableStateOf(Color(0xFF9C9CA5))
    var muted by mutableStateOf(Color(0xFFC7C7CE))
    var text by mutableStateOf(Color(0xFFF5F5F7))
    var plate by mutableStateOf(Color(0x0FFFFFFF))
    var plateStrong by mutableStateOf(Color(0x1FFFFFFF))
    var sunken by mutableStateOf(Color(0xFF19191C))
    val badgePlate get() = surface3
    val co2Pill get() = surface2

    val rCard = 22.dp
    val rControl = 16.dp
    val rPill = 16.dp

    var accent by mutableStateOf(DefaultAccent)
    // Text on an accent fill: dark on a pale accent (every Kav look), white on a deep one (YOU by day).
    val onAccent get() = if (accent.luminance() > 0.4f) Color(0xFF111114) else Color.White
    val route get() = accent
    val live get() = accent
    var routeIdle by mutableStateOf(Color(0xFF7E7E87))
    var problem by mutableStateOf(Color(0xFFE7C17A))
    var critical by mutableStateOf(Color(0xFFEE929A))
    // Moovit's green for a time or position measured live.
    var realtime by mutableStateOf(Color(0xFF04C876))
    val scheduled get() = muted

    val gap1 = 4.dp; val gap2 = 8.dp; val gap3 = 12.dp
    val gap4 = 16.dp; val gap5 = 20.dp; val gap6 = 24.dp; val gap8 = 32.dp

    // Every colour has to be set for every look, or a switch leaves strays from the last one.
    fun applyTheme(chosen: Look) {
        look = chosen
        palette(chosen)
    }

    /**
     * The chosen look as it should show now: OLED black while Battery Saver is on (when [autoOled]), the
     * phone's own colours for YOU. [userAccent] is the accent picked in Settings, used except by YOU and,
     * while it is still the default, by DUSK.
     */
    fun applyFor(ctx: android.content.Context, chosen: Look, userAccent: Color, autoOled: Boolean) {
        look = chosen
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        saving = autoOled && pm?.isPowerSaveMode == true
        val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        accent = userAccent
        when {
            saving -> palette(Look.OLED)
            chosen == Look.YOU && android.os.Build.VERSION.SDK_INT >= 31 -> {
                val cs = if (night) androidx.compose.material3.dynamicDarkColorScheme(ctx)
                    else androidx.compose.material3.dynamicLightColorScheme(ctx)
                light = !night
                bg = cs.surface; surface1 = cs.surfaceContainer; surface2 = cs.surfaceContainerHigh
                surface3 = cs.surfaceContainerHighest; surface4 = cs.outlineVariant
                border = cs.outlineVariant; borderStrong = cs.outline
                dim = cs.onSurfaceVariant.copy(alpha = .8f); muted = cs.onSurfaceVariant; text = cs.onSurface
                plate = cs.onSurface.copy(alpha = .06f); plateStrong = cs.onSurface.copy(alpha = .12f)
                sunken = cs.surfaceContainerLow; routeIdle = cs.outline
                problem = if (night) Color(0xFFE7C17A) else Color(0xFF9A6A00)
                critical = if (night) Color(0xFFEE929A) else Color(0xFFB3424E)
                // By day the deep primary, so accent-coloured text reads on the light surfaces.
                accent = cs.primary
            }
            chosen == Look.YOU -> palette(if (night) Look.DARK else Look.LIGHT)
            chosen == Look.DUSK -> {
                palette(Look.DUSK)
                if (userAccent == DefaultAccent) accent = Color(0xFFFFB547)
            }
            else -> palette(chosen)
        }
    }

    private fun palette(chosen: Look) {
        light = chosen == Look.LIGHT
        when (chosen) {
            Look.YOU -> palette(Look.DARK)
            Look.DUSK -> {
                bg = Color(0xFF0E1424); surface1 = Color(0xFF18213A)
                surface2 = Color(0xFF1F2A47); surface3 = Color(0xFF283454)
                surface4 = Color(0xFF36436A); border = Color(0xFF243050)
                borderStrong = Color(0xFF6A7699); dim = Color(0xFF97A2BE)
                muted = Color(0xFFC8CFE0); text = Color(0xFFEEF1F8)
                plate = Color(0x12FFFFFF); plateStrong = Color(0x22FFFFFF)
                sunken = Color(0xFF0B1020); routeIdle = Color(0xFF7A86A6)
                problem = Color(0xFFE7C17A); critical = Color(0xFFEE929A)
            }
            Look.DARK -> {
                bg = Color(0xFF101012); surface1 = Color(0xFF202023)
                surface2 = Color(0xFF2B2B30); surface3 = Color(0xFF343439)
                surface4 = Color(0xFF44444A); border = Color.White
                borderStrong = Color(0xFF74747D); dim = Color(0xFF9C9CA5)
                muted = Color(0xFFC7C7CE); text = Color(0xFFF5F5F7)
                plate = Color(0x0FFFFFFF); plateStrong = Color(0x1FFFFFFF)
                sunken = Color(0xFF19191C); routeIdle = Color(0xFF7E7E87)
                problem = Color(0xFFE7C17A); critical = Color(0xFFEE929A)
                realtime = Color(0xFF04C876)
            }
            Look.OLED -> {
                bg = Color(0xFF000000); surface1 = Color(0xFF000000)
                surface2 = Color(0xFF000000); surface3 = Color(0xFF000000)
                surface4 = Color(0xFF38383E); border = Color.White
                borderStrong = Color(0xFF6C6C75); dim = Color(0xFF9C9CA5)
                muted = Color(0xFFC7C7CE); text = Color(0xFFF5F5F7)
                plate = Color(0x14FFFFFF); plateStrong = Color(0x24FFFFFF)
                sunken = Color(0xFF000000); routeIdle = Color(0xFF7E7E87)
                problem = Color(0xFFE7C17A); critical = Color(0xFFEE929A)
                realtime = Color(0xFF04C876)
            }
            Look.LIGHT -> {
                bg = Color(0xFFF6F6F3); surface1 = Color(0xFFEBEBE7)
                surface2 = Color(0xFFE0E0DC); surface3 = Color(0xFFD5D5D1)
                surface4 = Color(0xFFC3C3BF); border = Color(0xFFDADAD6)
                borderStrong = Color(0xFF97979F); dim = Color(0xFF6F6F78)
                muted = Color(0xFF494951); text = Color(0xFF16161A)
                plate = Color(0x0D000000); plateStrong = Color(0x1A000000)
                sunken = Color(0xFFEFEFEB); routeIdle = Color(0xFFA6A6AE)
                problem = Color(0xFF9A6A00); critical = Color(0xFFB3424E)
                realtime = Color(0xFF00804C)
            }
        }
    }
}

object Shown {
    var co2 by mutableStateOf(false)
    var twelveHour by mutableStateOf(false)
}

val Display get() = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
    fontSize = 21.sp, color = K.text,
)
val DisplayItalic get() = Display.copy(fontWeight = FontWeight.Medium)
val Mono get() = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = K.muted)

private fun scheme() = (if (K.light) lightColorScheme() else darkColorScheme()).copy(
    primary = K.text, onPrimary = K.bg,
    primaryContainer = K.surface3, onPrimaryContainer = K.text,
    inversePrimary = K.surface4,
    secondary = K.muted, onSecondary = K.bg,
    secondaryContainer = K.surface2, onSecondaryContainer = K.text,
    tertiary = K.muted, onTertiary = K.bg,
    tertiaryContainer = K.surface2, onTertiaryContainer = K.text,
    background = K.bg, onBackground = K.text,
    surface = K.bg, onSurface = K.text,
    surfaceVariant = K.surface3, onSurfaceVariant = K.muted,
    surfaceTint = K.surface3,
    inverseSurface = K.muted, inverseOnSurface = K.bg,
    surfaceContainerLowest = K.bg, surfaceContainerLow = K.sunken,
    surfaceContainer = K.surface1, surfaceContainerHigh = K.surface2,
    surfaceContainerHighest = K.surface3,
    outline = K.border, outlineVariant = K.border,
    error = K.critical, onError = K.bg,
    errorContainer = K.surface3, onErrorContainer = K.critical,
)

@Composable
fun KavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme(),
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 14.sp, color = K.text),
            bodyMedium = TextStyle(fontSize = 13.sp, color = K.text),
            bodySmall = TextStyle(fontSize = 11.sp, color = K.dim),
        ),
        content = content,
    )
}
