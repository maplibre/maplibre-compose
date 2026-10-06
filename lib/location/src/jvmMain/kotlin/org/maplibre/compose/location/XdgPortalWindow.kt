package org.maplibre.compose.location

/**
 * A window that an XDG portal can use as the parent for a system dialog.
 *
 * Values may be added in minor releases; use an `else` branch when matching. The XDG portal
 * specification may add parent window kinds beyond [X11] and [Wayland].
 */
public sealed interface XdgPortalWindow {
  /** An X11 top-level window. */
  public data class X11(public val windowId: Long) : XdgPortalWindow {
    init {
      require(windowId > 0) { "An X11 window ID must be positive, was $windowId" }
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
 * Keeps [XdgPortalWindow] open: callers' `when` needs an `else` branch. The library never returns
 * it.
 */
internal data object UnspecifiedXdgPortalWindow : XdgPortalWindow
