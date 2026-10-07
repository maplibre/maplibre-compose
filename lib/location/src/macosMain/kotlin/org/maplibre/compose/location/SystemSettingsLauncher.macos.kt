package org.maplibre.compose.location

import platform.AppKit.NSWorkspace
import platform.Foundation.NSURL

/** Opens macOS Location Services settings, where application location grants are managed. */
public actual class SystemSettingsLauncher {
  public actual val canOpenApplicationSettings: Boolean = true

  /**
   * Opens the Location Services pane in System Settings, which holds the services toggle and the
   * per-application location permissions.
   */
  public actual fun openApplicationSettings(): Boolean = openLocationServicesPane()

  public actual val canOpenLocationServicesSettings: Boolean = true

  /** Opens the same pane as [openApplicationSettings], which also holds the services toggle. */
  public actual fun openLocationServicesSettings(): Boolean = openLocationServicesPane()

  private fun openLocationServicesPane(): Boolean =
    NSURL.URLWithString(
        "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices"
      )
      ?.let { NSWorkspace.sharedWorkspace.openURL(it) } ?: false
}
