package org.maplibre.compose.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import java.awt.Window
import org.maplibre.compose.desktop.skiko.AwtComposeGpuHost
import org.maplibre.compose.mlnffi.LocalMlnFfiRuntimeOptions

/**
 * The [ComposeGpuHost] maps in this composition render against.
 *
 * Install one with [ProvideMapHost]. An AWT-backed Compose window can obtain its host from
 * [rememberAwtComposeGpuHost]. Prefer [ProvideMapHost] over setting this directly.
 *
 * This is `static`: changing it rebuilds the map's GPU bridge rather than recomposing it.
 */
public val LocalComposeGpuHost: ProvidableCompositionLocal<ComposeGpuHost> =
  staticCompositionLocalOf {
    error("No ComposeGpuHost is installed. Wrap this window's content in " + "ProvideMapHost(...).")
  }

/**
 * Remembers a [ComposeGpuHost] backed by Compose Desktop's Skiko layer inside [window].
 *
 * The window is explicit because each AWT window owns a distinct GPU context. The returned host
 * confines all reflective Skiko lookup to this window and runs GPU work on the AWT event thread.
 */
@Composable
public fun rememberAwtComposeGpuHost(window: Window): ComposeGpuHost =
  remember(window) { AwtComposeGpuHost(window) }

/**
 * Renders maps in [content] against [host].
 *
 * ```kotlin
 * ProvideMapHost(
 *   host = rememberMyComposeGpuHost(),
 *   runtimeOptions = DesktopRuntimeOptions(cachePath = desktopCachePath("com.example.myapp")),
 * ) {
 *   MaplibreMap()
 * }
 * ```
 */
@Composable
public fun ProvideMapHost(
  host: ComposeGpuHost,
  runtimeOptions: DesktopRuntimeOptions,
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalComposeGpuHost provides host,
    LocalMlnFfiRuntimeOptions provides runtimeOptions.toMlnFfiRuntimeOptions(),
    content = content,
  )
}
