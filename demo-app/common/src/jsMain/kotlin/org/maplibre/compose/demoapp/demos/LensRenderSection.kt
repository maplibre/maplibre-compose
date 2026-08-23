package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.DemoSettings
import org.maplibre.compose.map.RenderOptions

@Composable
actual fun LensRenderSection(
  settings: DemoSettings,
  lensOptions: RenderOptions,
  onLensChange: (RenderOptions) -> Unit,
) {}

actual val LensRenderOptionsDefault: RenderOptions = RenderOptions.Standard
