package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.map.MapUiOptions

@Composable
actual fun LensRenderSection(lensOptions: MapUiOptions, onLensChange: (MapUiOptions) -> Unit) {}

actual val LensUiOptionsDefault: MapUiOptions = MapUiOptions.Standard
