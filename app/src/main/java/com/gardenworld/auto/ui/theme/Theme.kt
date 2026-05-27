package com.gardenworld.auto.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)
private val LightColorScheme = lightColorScheme(primary = Purple40, secondary = PurpleGrey40, tertiary = Pink40)

@Composable
fun GardenWorldAutoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkTheme
        }
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

val Purple80 = androidx.compose.ui.graphics.Color(0xFFD0BCFF)
val PurpleGrey80 = androidx.compose.ui.graphics.Color(0xFFCCC2DC)
val Pink80 = androidx.compose.ui.graphics.Color(0xFFEFB8C8)
val Purple40 = androidx.compose.ui.graphics.Color(0xFF6650a4)
val PurpleGrey40 = androidx.compose.ui.graphics.Color(0xFF625b71)
val Pink40 = androidx.compose.ui.graphics.Color(0xFF7D5260)
