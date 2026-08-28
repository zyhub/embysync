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
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    surfaceTint = Color.Transparent, // 🌟 禁用 Material 3 表面粉红/泛红染色
    onPrimary = Color.White,
    onBackground = Color(0xFF1C1C1E),
    onSurface = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF64748B)
)

val DarkColorScheme = darkColorScheme(
    primary = AppleRed,
    secondary = SyncBlue,
    tertiary = EmbyGreen,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = Color(0xFF2C2C2E),
    surfaceTint = Color.Transparent, // 🌟 禁用染色
    onPrimary = Color.White,
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF8E8E93)
)
