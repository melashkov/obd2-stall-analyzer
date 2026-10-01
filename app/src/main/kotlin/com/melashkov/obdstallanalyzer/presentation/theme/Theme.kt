package com.melashkov.obdstallanalyzer.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StallAnalyzerColors = darkColorScheme(
    primary = Teal,
    secondary = Blue,
    background = Navy,
    surface = Panel,
    surfaceVariant = PanelDark,
    outline = PanelBorder,
    error = Red,
    onPrimary = Navy,
    onSecondary = Navy,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Muted,
    onError = Navy,
)

@Composable
internal fun StallAnalyzerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StallAnalyzerColors,
        content = content,
    )
}
