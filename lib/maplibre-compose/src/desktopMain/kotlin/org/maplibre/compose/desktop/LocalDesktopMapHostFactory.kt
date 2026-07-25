package org.maplibre.compose.desktop

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import org.maplibre.compose.desktop.skiko.SkikoDesktopMapHostFactory

/**
 * The [DesktopMapHostFactory] maps in this composition use.
 *
 * Defaults to the Compose Desktop host built on Skiko. Provide a different factory to render maps
 * in a Compose host that supplies its own GPU context:
 * ```kotlin
 * CompositionLocalProvider(LocalDesktopMapHostFactory provides MyHostFactory) {
 *   MaplibreMap()
 * }
 * ```
 *
 * This is `static`: changing it recreates the map's host rather than recomposing it, which is the
 * intended behavior since the GPU objects on both sides of the bridge belong to the host.
 */
public val LocalDesktopMapHostFactory: ProvidableCompositionLocal<DesktopMapHostFactory> =
  staticCompositionLocalOf {
    SkikoDesktopMapHostFactory
  }
