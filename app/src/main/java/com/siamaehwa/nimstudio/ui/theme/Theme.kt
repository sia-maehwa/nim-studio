package com.siamaehwa.nimstudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NimGreen = Color(0xFF76B900)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BD54A),
    onPrimary = Color(0xFF122000),
    primaryContainer = Color(0xFF2E4200),
    onPrimaryContainer = Color(0xFFC9EE8E),
    secondary = Color(0xFFB9C8A4),
    onSecondary = Color(0xFF243317),
    secondaryContainer = Color(0xFF3A4A2C),
    onSecondaryContainer = Color(0xFFD5E6BE),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE4E6EA),
    surface = Color(0xFF12161C),
    onSurface = Color(0xFFE4E6EA),
    surfaceVariant = Color(0xFF232B34),
    onSurfaceVariant = Color(0xFFB3BCC7),
    outline = Color(0xFF5B6672),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F6B00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEFA0),
    onPrimaryContainer = Color(0xFF102000),
    secondary = Color(0xFF55624A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E8C4),
    onSecondaryContainer = Color(0xFF131F0A),
    background = Color(0xFFFAFBF7),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFFDFDFA),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE1E4DA),
    onSurfaceVariant = Color(0xFF454B42),
    outline = Color(0xFF757B70),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun NimStudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
