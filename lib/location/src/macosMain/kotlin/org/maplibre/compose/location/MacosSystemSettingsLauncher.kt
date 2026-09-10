package org.maplibre.compose.location

import platform.AppKit.NSWorkspace
import platform.Foundation.NSURL

/** Opens macOS Location Services settings, where application location grants are managed. */
public class MacosSystemSettingsLauncher : SystemSettingsLauncher {
  override val canOpenApplicationSettings: Boolean = false

  override fun openApplicationSettings(): Boolean = false

  override val canOpenLocationServicesSettings: Boolean = true

  override fun openLocationServicesSettings(): Boolean =
    NSURL.URLWithString(
        "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices"
      )
      ?.let { NSWorkspace.sharedWorkspace.openURL(it) } ?: false
}
