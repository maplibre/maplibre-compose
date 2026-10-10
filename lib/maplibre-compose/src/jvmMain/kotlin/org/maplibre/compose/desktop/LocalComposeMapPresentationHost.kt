package org.maplibre.compose.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
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
 * Replacing the host rebuilds the map's GPU bridge, even when the two host objects compare equal.
 */
public val LocalComposeMapPresentationHost: ProvidableCompositionLocal<ComposeMapPresentationHost> =
  compositionLocalOf(referentialEqualityPolicy()) {
    error(
      "No ComposeMapPresentationHost is installed. Wrap this window's content in " +
        "ProvideMapPresentationHost(...)."
    )
  }

/**
 * Remembers a [ComposeMapPresentationHost] for an AWT-backed Compose [window], such as the window
 * of `singleWindowApplication` or `Window`.
 *
 * On Linux, the host also gives XDG portals the window as the parent of system dialogs, such as the
 * location permission prompt.
 *
 * @param window The window whose content shows the maps: each AWT window has its own GPU context.
 * @throws IllegalStateException if the current operating system isn't macOS, Windows, or Linux.
 */
@Composable
public fun rememberAwtComposeMapPresentationHost(window: Window): ComposeMapPresentationHost =
  remember(window) { AwtComposeMapPresentationHost(window).presentationHost }

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
@NonSkippableComposable
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
