package org.maplibre.compose.demoapp

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

actual val paletteModeOptions: List<PaletteMode> =
  listOf(PaletteMode.Tonal, PaletteMode.Neutral, PaletteMode.Vibrant, PaletteMode.Expressive)

@Composable actual fun rememberSystemColorScheme(dark: Boolean): ColorScheme? = null
