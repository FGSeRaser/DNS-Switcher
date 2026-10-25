package de.fgseraser.dnsswitcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark look like the other FGSeRaser tools. */
object Palette {
    val Back = Color(0xFF141518)
    val Surface = Color(0xFF1D1E22)
    val SurfaceHigh = Color(0xFF26282D)
    val Border = Color(0xFF32343A)
    val Accent = Color(0xFF4DA3FF)
    val AccentSoft = Color(0xFF162C48)
    val Good = Color(0xFF5CB860)
    val Warning = Color(0xFFEEB640)
    val Danger = Color(0xFFE5534B)
    val Text = Color(0xFFEBECF0)
    val Muted = Color(0xFF8C8F98)
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    primaryContainer = Palette.AccentSoft,
    onPrimaryContainer = Palette.Accent,
    secondary = Palette.Accent,
    secondaryContainer = Palette.AccentSoft,
    onSecondaryContainer = Palette.Text,
    background = Palette.Back,
    onBackground = Palette.Text,
    surface = Palette.Back,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Surface,
    onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    surfaceContainerLow = Palette.Surface,
    outline = Palette.Border,
    outlineVariant = Palette.Border,
    error = Palette.Danger
)

@Composable
fun DnsSwitcherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
