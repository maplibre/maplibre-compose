package org.maplibre.compose.location

/** The browser exposes no way to open its settings, so every screen is unavailable. */
public actual class SystemSettingsLauncher {
  public actual val canOpenApplicationSettings: Boolean = false

  public actual fun openApplicationSettings(): Boolean = false

  public actual val canOpenLocationServicesSettings: Boolean = false

  public actual fun openLocationServicesSettings(): Boolean = false
}
