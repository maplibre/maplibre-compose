package org.maplibre.compose.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import java.awt.Window
import org.maplibre.compose.desktop.skiko.AwtComposeMapHost
import org.maplibre.compose.location.LocalXdgPortalWindow

/**
 * The [ComposeMapHost] maps in this composition render against.
 *
 * Install one with [ProvideMapHost]. An AWT-backed Compose window can obtain its host from
 * [rememberAwtComposeMapHost]. Prefer [ProvideMapHost] over setting this directly.
 *
 * This is `static`: changing it rebuilds the map's GPU bridge rather than recomposing it.
 */
public val LocalComposeMapHost: ProvidableCompositionLocal<ComposeMapHost> =
  staticCompositionLocalOf {
    error("No ComposeMapHost is installed. Wrap this window's content in " + "ProvideMapHost(...).")
  }

/**
 * Remembers a [ComposeMapHost] backed by Compose Desktop's Skiko layer inside [window].
 *
 * The window is explicit because each AWT window owns a distinct GPU context. The returned host
 * confines all reflective Skiko lookup to this window and runs GPU work on the AWT event thread.
 */
@Composable
public fun rememberAwtComposeMapHost(window: Window): ComposeMapHost =
  remember(window) { AwtComposeMapHost(window) }

/**
 * Renders maps in [content] against [host].
 *
 * ```kotlin
 * ProvideMapHost(
 *   host = rememberMyComposeMapHost(),
 * ) {
 *   MaplibreMap()
 * }
 * ```
 */
@Composable
public fun ProvideMapHost(host: ComposeMapHost, content: @Composable () -> Unit) {
  CompositionLocalProvider(
    LocalComposeMapHost provides host,
    LocalXdgPortalWindow provides host.xdgPortalWindow,
    content = content,
  )
}
