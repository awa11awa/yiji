package cn.yiji.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Pine = Color(0xFF246B5B)
val Paper = Color(0xFFF7F8F3)
val Amber = Color(0xFF986413)
val SoftGreen = Color(0xFFE8F0E8)

@Composable
fun YijiTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF9DD4BC), secondary = Color(0xFFC9DDA0),
        background = Color(0xFF151C18), surface = Color(0xFF1D2620))
    else lightColorScheme(primary = Pine, onPrimary = Color.White, secondary = Color(0xFF586B30),
        background = Paper, surface = Color(0xFFFEFEFA), surfaceVariant = SoftGreen,
        primaryContainer = Color(0xFFD7ECDE), onPrimaryContainer = Color(0xFF14382F))
    MaterialTheme(colorScheme = colors, content = content)
}
