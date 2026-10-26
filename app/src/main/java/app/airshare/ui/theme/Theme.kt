package app.airshare.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Dark = darkColorScheme(
    primary = Color(0xFF7AA2FF), onPrimary = Color(0xFF0B1B3F),
    primaryContainer = Color(0xFF1A2742), onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF9AA4B2), tertiary = Color(0xFF3DD6B0), onTertiary = Color(0xFF00382C),
    background = Color(0xFF0E1116), onBackground = Color(0xFFE8EBF0),
    surface = Color(0xFF151A21), onSurface = Color(0xFFE8EBF0),
    surfaceVariant = Color(0xFF1C222B), onSurfaceVariant = Color(0xFF9AA4B2),
    outline = Color(0xFF2B3440), outlineVariant = Color(0xFF222A34), error = Color(0xFFFF8A80),
)

private val Light = lightColorScheme(
    primary = Color(0xFF3F6BE0), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EBFF), onPrimaryContainer = Color(0xFF0F2A6B),
    secondary = Color(0xFF5F6B7A), tertiary = Color(0xFF0E9C7E), onTertiary = Color.White,
    background = Color(0xFFF6F7F9), onBackground = Color(0xFF12161C),
    surface = Color.White, onSurface = Color(0xFF12161C),
    surfaceVariant = Color(0xFFEDEFF3), onSurfaceVariant = Color(0xFF5F6B7A),
    outline = Color(0xFFD5DAE1), outlineVariant = Color(0xFFE4E8EE), error = Color(0xFFB3261E),
)

private val Sans = FontFamily.SansSerif
private val AirTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp),
)

private val AirShapes = Shapes(
    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(26.dp),
)

@Composable
fun AirShareTheme(mode: String, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as? Activity)?.window ?: return@SideEffect
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AirTypography, shapes = AirShapes, content = content)
}
