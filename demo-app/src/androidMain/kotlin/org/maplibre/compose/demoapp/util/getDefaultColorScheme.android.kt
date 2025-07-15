package org.maplibre.compose.demoapp.util

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun getDefaultColorScheme(isDark: Boolean): ColorScheme {
  val dynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
  return when {
    dynamicColor && isDark -> dynamicDarkColorScheme(LocalContext.current)
    dynamicColor && !isDark -> dynamicLightColorScheme(LocalContext.current)
    isDark -> darkColorScheme()
    else -> lightColorScheme()
  }
}
