package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.map.RenderOptions

@Composable
actual fun LensRenderSection(lensOptions: RenderOptions, onLensChange: (RenderOptions) -> Unit) {}

actual val LensRenderOptionsDefault: RenderOptions = RenderOptions.Standard
