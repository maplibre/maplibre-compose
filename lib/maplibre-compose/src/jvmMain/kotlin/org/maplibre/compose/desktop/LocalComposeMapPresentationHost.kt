package org.maplibre.compose.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import java.awt.Window
import org.maplibre.compose.desktop.skiko.AwtComposeMapPresentationHost
import org.maplibre.compose.location.LocalXdgPortalWindow

/**
 * The [ComposeMapPresentationHost] maps in this composition render against.
 *
 * Install one with [ProvideMapPresentationHost]. An AWT-backed Compose window can obtain its host
 * from [rememberAwtComposeMapPresentationHost]. Prefer [ProvideMapPresentationHost] over setting
 * this directly.
 *
 * This is `static`: changing it rebuilds the map's GPU bridge rather than recomposing it.
 */
public val LocalComposeMapPresentationHost: ProvidableCompositionLocal<ComposeMapPresentationHost> =
  staticCompositionLocalOf {
    error(
      "No ComposeMapPresentationHost is installed. Wrap this window's content in " +
        "ProvideMapPresentationHost(...)."
    )
  }

/**
 * Remembers a [ComposeMapPresentationHost] backed by Compose Desktop's Skiko layer inside [window].
 *
 * The window is explicit because each AWT window owns a distinct GPU context. The returned host
 * confines all reflective Skiko lookup to this window and runs GPU work on the AWT event thread.
 */
@Composable
public fun rememberAwtComposeMapPresentationHost(window: Window): ComposeMapPresentationHost =
  remember(window) { AwtComposeMapPresentationHost(window) }

/**
 * Renders maps in [content] against [host].
 *
 * ```kotlin
 * ProvideMapPresentationHost(
 *   host = rememberMyComposeMapPresentationHost(),
 * ) {
 *   MaplibreMap()
 * }
 * ```
 */
@Composable
public fun ProvideMapPresentationHost(
  host: ComposeMapPresentationHost,
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalComposeMapPresentationHost provides host,
    LocalXdgPortalWindow provides host.xdgPortalWindow,
    content = content,
  )
}
