package org.maplibre.compose.location

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/** A window that an XDG portal can use as the parent for a system dialog. */
public sealed interface XdgPortalWindow {
  /** An X11 top-level window. */
  @Immutable
  public data class X11(public val windowId: Long) : XdgPortalWindow {
    init {
      require(windowId > 0) { "An X11 window ID must be positive" }
    }
  }

  /**
   * A Wayland top-level surface that can export an xdg-foreign handle.
   *
   * The host owns the Wayland connection and event loop. It must keep the export alive while
   * `action` runs and release it afterward. Pass null to `action` when the compositor does not
   * support xdg-foreign.
   */
  public interface Wayland : XdgPortalWindow {
    public suspend fun <T> withXdgForeignHandle(action: suspend (String?) -> T): T
  }
}

/**
 * The window that XDG portals use to parent system dialogs, such as the Linux location permission
 * prompt.
 *
 * Defaults to null, which asks the portal to present its dialog without a parent. MapLibre
 * Compose's `ProvideMapHost` installs the map host's window; an application without a map host
 * installs one directly.
 */
public val LocalXdgPortalWindow: ProvidableCompositionLocal<XdgPortalWindow?> =
  staticCompositionLocalOf {
    null
  }
