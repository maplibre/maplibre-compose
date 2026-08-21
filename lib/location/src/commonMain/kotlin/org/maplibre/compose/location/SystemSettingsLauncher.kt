package org.maplibre.compose.location

import androidx.compose.runtime.Composable

/** Opens system settings screens related to location. */
public interface SystemSettingsLauncher {
  /** Whether [openApplicationSettings] can open a screen on this platform. */
  public val canOpenApplicationSettings: Boolean

  /**
   * Opens the screen where the user manages this application's location permission, and returns
   * whether the screen opened.
   *
   * Android opens the application's details screen in the system settings. iOS opens the
   * application's page in the Settings app. macOS opens the Location Services pane in System
   * Settings. Windows opens the location privacy page in Settings. Linux and web expose no such
   * screen, so the call returns `false`.
   */
  public fun openApplicationSettings(): Boolean

  /** Whether [openLocationServicesSettings] can open a screen on this platform. */
  public val canOpenLocationServicesSettings: Boolean

  /**
   * Opens the screen where the user turns system location services on, and returns whether the
   * screen opened.
   *
   * Android opens the location settings screen. macOS opens the Location Services pane in System
   * Settings. Windows opens the location privacy page in Settings. iOS, Linux, and web expose no
   * such screen, so the call returns `false`.
   */
  public fun openLocationServicesSettings(): Boolean
}

/** Creates and remembers the platform [SystemSettingsLauncher]. */
@Composable public expect fun rememberSystemSettingsLauncher(): SystemSettingsLauncher
