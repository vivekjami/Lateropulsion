package com.lateropulsion.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF1F4E79), onPrimary = Color.White, primaryContainer = Color(0xFFD6E6F5), onPrimaryContainer = Color(0xFF0B2540),
    secondary = Color(0xFF2E7D32), onSecondary = Color.White, secondaryContainer = Color(0xFFDDF2DE),
    tertiary = Color(0xFFB26A00), error = Color(0xFFB3261E), onError = Color.White, errorContainer = Color(0xFFF9DEDC),
    background = Color(0xFFFBFCFE), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE7ECF2), onSurfaceVariant = Color(0xFF44474E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8FC1EE), onPrimary = Color(0xFF00335A), primaryContainer = Color(0xFF14497A),
    secondary = Color(0xFF9BD79B), tertiary = Color(0xFFF2B866), error = Color(0xFFF2B8B5),
)

/** Large type and 56 dp touch targets throughout: therapists work standing, sometimes gloved (README §9). */
val LpTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun LpTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = LpTypography, content = content)
}
