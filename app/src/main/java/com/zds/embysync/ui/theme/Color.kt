package com.zds.embysync.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val AppleRed = Color(0xFFFA2D48)
val EmbyGreen = Color(0xFF34C759)
val SyncBlue = Color(0xFF007AFF)
val SyncOrange = Color(0xFFFF9500)
val DarkSurface = Color(0xFF1C1C1E)
val DarkBackground = Color(0xFF000000)

val LightColorScheme = lightColorScheme(
    primary = AppleRed,
    secondary = SyncBlue,
    tertiary = EmbyGreen,
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE5E5EA),
    onPrimary = Color.White,
    onBackground = Color(0xFF1C1C1E),
    onSurface = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93)
)

val DarkColorScheme = darkColorScheme(
    primary = AppleRed,
    secondary = SyncBlue,
    tertiary = EmbyGreen,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = Color(0xFF2C2C2E),
    onPrimary = Color.White,
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF8E8E93)
)
