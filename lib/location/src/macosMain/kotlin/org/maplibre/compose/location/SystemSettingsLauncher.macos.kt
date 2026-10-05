package org.maplibre.compose.location

import platform.AppKit.NSWorkspace
import platform.Foundation.NSURL

/** Opens macOS Location Services settings, where application location grants are managed. */
public actual class SystemSettingsLauncher {
  public actual val canOpenApplicationSettings: Boolean = false

  public actual fun openApplicationSettings(): Boolean = false

  public actual val canOpenLocationServicesSettings: Boolean = true

  public actual fun openLocationServicesSettings(): Boolean =
    NSURL.URLWithString(
        "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices"
      )
      ?.let { NSWorkspace.sharedWorkspace.openURL(it) } ?: false
}
