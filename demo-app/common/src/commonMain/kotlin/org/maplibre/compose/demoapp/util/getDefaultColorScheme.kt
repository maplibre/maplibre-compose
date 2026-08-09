package org.maplibre.compose.demoapp.util

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable expect fun getDefaultColorScheme(isDark: Boolean = isSystemInDarkTheme()): ColorScheme
