package org.maplibre.compose.location

import androidx.compose.runtime.Composable

/** The browser exposes no way to open its settings, so every screen is unavailable. */
public object BrowserSystemSettingsLauncher : SystemSettingsLauncher {
  override val canOpenApplicationSettings: Boolean = false

  override fun openApplicationSettings(): Boolean = false

  override val canOpenLocationServicesSettings: Boolean = false

  override fun openLocationServicesSettings(): Boolean = false
}

@Composable
public actual fun rememberSystemSettingsLauncher(): SystemSettingsLauncher =
  BrowserSystemSettingsLauncher
