package org.maplibre.maplibrecompose.demoapp

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    MapLibre.getInstance(this)
    HttpRequestUtil.setLogEnabled(false)
    enableEdgeToEdge()
    setContent { DemoApp() }
  }
}

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

actual object Platform {
  actual val isAndroid: Boolean = true
  actual val isIos: Boolean = false
  actual val isDesktop: Boolean = false
  actual val isWeb: Boolean = false
}
