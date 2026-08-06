package org.maplibre.compose.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import org.maplibre.compose.desktop.skiko.SkikoComposeGpuHost

/**
 * The [DesktopComposeGpuHost] maps in this composition render against.
 *
 * Defaults to Compose Desktop's own AWT window, so an application running that needs to provide
 * nothing. Prefer [ProvideMapHost] over setting this directly.
 *
 * This is `static`: changing it rebuilds the map's GPU bridge rather than recomposing it.
 */
public val LocalDesktopComposeGpuHost: ProvidableCompositionLocal<DesktopComposeGpuHost> =
  staticCompositionLocalOf {
    SkikoComposeGpuHost
  }

/**
 * Renders maps in [content] against [host] rather than Compose Desktop's own AWT window.
 *
 * ```kotlin
 * ProvideMapHost(rememberMyComposeGpuHost()) {
 *   MaplibreMap()
 * }
 * ```
 */
@Composable
public fun ProvideMapHost(host: DesktopComposeGpuHost, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalDesktopComposeGpuHost provides host, content = content)
}
