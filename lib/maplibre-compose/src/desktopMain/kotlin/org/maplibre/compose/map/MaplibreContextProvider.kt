package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.FrameWindowScope
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.maplibre.compose.generated.Res

@Composable
@OptIn(ExperimentalResourceApi::class)
public fun FrameWindowScope.MaplibreContextProvider(content: @Composable () -> Unit) {
  val refreshRate = window.graphicsConfiguration.device.displayMode.refreshRate
  var webviewHtml by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(webviewHtml) {
    if (webviewHtml != null) return@LaunchedEffect
    webviewHtml = Res.readBytes("files/maplibre-compose-webview.html").toString(Charsets.UTF_8)
  }

  if (webviewHtml == null) return

  val context = remember(refreshRate, webviewHtml) { MaplibreContext(refreshRate, webviewHtml!!) }

  CompositionLocalProvider(LocalMaplibreContext provides context) { content() }
}

internal data class MaplibreContext(val refreshRate: Int, val webviewHtml: String)

internal val LocalMaplibreContext =
  compositionLocalOf<MaplibreContext> {
    error("No MaplibreContext provided; wrap your app with MaplibreContextProvider")
  }
