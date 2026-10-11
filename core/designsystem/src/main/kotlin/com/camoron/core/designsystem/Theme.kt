package com.camoron.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CamoronColors = darkColorScheme(
    primary = Color(0xFFFFC94D),
    onPrimary = Color(0xFF1F1500),
    secondary = Color(0xFF8FD3FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFBDBDBD),
)

/** Camera apps stay dark regardless of system theme so the preview is judged against black. */
@Composable
fun CamoronTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CamoronColors, content = content)
}
