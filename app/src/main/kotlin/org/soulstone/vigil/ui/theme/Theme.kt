package org.soulstone.vigil.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Catppuccin Mocha — VIGIL matches OVERWATCH's palette.
private val Base = Color(0xFF1E1E2E)
private val Mantle = Color(0xFF181825)
private val Surface0 = Color(0xFF313244)
private val Surface1 = Color(0xFF45475A)
private val Text = Color(0xFFCDD6F4)
private val Subtext = Color(0xFFA6ADC8)
private val Blue = Color(0xFF89B4FA)
private val Mauve = Color(0xFFCBA6F7)
val VigilGreen = Color(0xFFA6E3A1)
val VigilPeach = Color(0xFFFAB387)
val VigilRed = Color(0xFFF38BA8)

private val MochaScheme = darkColorScheme(
    primary = Blue,
    onPrimary = Base,
    secondary = Mauve,
    background = Base,
    onBackground = Text,
    surface = Mantle,
    onSurface = Text,
    surfaceVariant = Surface0,
    onSurfaceVariant = Subtext,
    outline = Surface1,
    error = VigilRed,
    onError = Base
)

@Composable
fun VigilTheme(content: @Composable () -> Unit) {
    // VIGIL is dark/Mocha regardless of system setting, matching OVERWATCH.
    MaterialTheme(colorScheme = MochaScheme, content = content)
}
