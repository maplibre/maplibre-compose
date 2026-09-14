package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.compose.overlay.LocalCameraPadding
import org.maplibre.compose.overlay.MapOverlay

@Composable
internal fun Modifier.controlPadding(): Modifier {
  val padding = LocalCameraPadding.current
  return padding(padding)
    .consumeWindowInsets(padding)
    .windowInsetsPadding(WindowInsets.safeDrawing)
    .padding(MapOverlay.Spacing)
}
