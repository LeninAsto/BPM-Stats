package com.leninasto.bpmstats.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Sage80,
    secondary = Mist80,
    tertiary = Warm80,
    background = androidx.compose.ui.graphics.Color(0xFF101613),
    surface = androidx.compose.ui.graphics.Color(0xFF151D19),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF22312B),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF1C4B3F),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF22384C),
)

private val LightColorScheme = lightColorScheme(
    primary = Sage40,
    secondary = Mist40,
    tertiary = Warm40,
    background = androidx.compose.ui.graphics.Color(0xFFF5FAF6),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE2EEE8),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFCFEFE2),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFD9E7F7),

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun BPMStatsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
