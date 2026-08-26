package org.maplibre.compose.demoapp

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

actual val paletteModeOptions: List<PaletteMode> =
  listOf(
    PaletteMode.System,
    PaletteMode.Tonal,
    PaletteMode.Neutral,
    PaletteMode.Vibrant,
    PaletteMode.Expressive,
  )

@Composable
actual fun rememberSystemColorScheme(dark: Boolean): ColorScheme? {
  val context = LocalContext.current
  return remember(context, dark) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
      null
    }
  }
}
