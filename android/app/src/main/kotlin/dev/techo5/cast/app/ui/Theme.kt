package dev.techo5.cast.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.techo5.cast.app.R

// Tokens from the "TECHO5 Cast" design system in Stitch: a dark, blue-black surface stack with one
// teal accent for the primary action and the live state; amber only for warnings.
private val Scheme = darkColorScheme(
    primary = Color(0xFF3CDDC7),
    onPrimary = Color(0xFF004941),
    primaryContainer = Color(0xFF11C9B4),
    onPrimaryContainer = Color(0xFF003A33),
    secondary = Color(0xFF7BD1FA),
    onSecondary = Color(0xFF00465D),
    secondaryContainer = Color(0xFF004156),
    onSecondaryContainer = Color(0xFF73CAF2),
    tertiary = Color(0xFFFFD16F),
    onTertiary = Color(0xFF614700),
    tertiaryContainer = Color(0xFFFCC025),
    onTertiaryContainer = Color(0xFF563E00),
    error = Color(0xFFFA746F),
    onError = Color(0xFF490006),
    errorContainer = Color(0xFF871F21),
    onErrorContainer = Color(0xFFFF9993),
    background = Color(0xFF070F18),
    onBackground = Color(0xFFD5E7FF),
    surface = Color(0xFF070F18),
    onSurface = Color(0xFFD5E7FF),
    surfaceVariant = Color(0xFF152739),
    onSurfaceVariant = Color(0xFF9BADC4),
    outline = Color(0xFF66778C),
    outlineVariant = Color(0xFF38495D),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF091420),
    surfaceContainer = Color(0xFF0D1B28),
    surfaceContainerHigh = Color(0xFF122130),
    surfaceContainerHighest = Color(0xFF152739),
    surfaceBright = Color(0xFF1C2D40),
    surfaceDim = Color(0xFF070F18),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val Manrope = FontFamily(listOf(500, 600, 700, 800).map { variable(R.font.manrope, it) })
private val Inter = FontFamily(listOf(400, 500, 600).map { variable(R.font.inter, it) })

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CastTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
