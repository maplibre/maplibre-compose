package org.maplibre.compose.desktop

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

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
    // TODO(maplibre-native-ffi): replace with the Skiko host factory once the native bridges are
    // ported. Until then a map only renders with a factory supplied by the application.
    UnavailableDesktopMapHostFactory
  }

/**
 * Stands in until the default Skiko host lands, reporting a diagnostic rather than failing
 * obscurely.
 */
internal object UnavailableDesktopMapHostFactory : DesktopMapHostFactory {
  override val supportedBackends: Set<DesktopBackendPair> = emptySet()

  override val description: String =
    "no desktop map host (the default Compose Desktop host is not implemented yet)"

  override fun create(producer: MapRenderBackend): DesktopMapHostResult =
    DesktopMapHostResult.Unsupported(
      "MapLibre Compose has no default desktop map host yet. Provide one through " +
        "LocalDesktopMapHostFactory."
    )
}
