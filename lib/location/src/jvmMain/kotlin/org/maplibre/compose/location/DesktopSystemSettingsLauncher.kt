package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.IOException

/** Opens the desktop settings screen related to location. */
public class DesktopSystemSettingsLauncher : SystemSettingsLauncher {

  private val command: List<String>? =
    with(System.getProperty("os.name").orEmpty().lowercase()) {
      when {
        contains("mac") ->
          listOf(
            "open",
            "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices",
          )
        contains("win") -> listOf("explorer.exe", "ms-settings:privacy-location")
        else -> null
      }
    }

  override val canOpenApplicationSettings: Boolean = command != null

  /**
   * Opens the screen that holds the services toggle and the per-application location permissions:
   * the Location Services pane in System Settings on macOS, and the location privacy page in
   * Settings on Windows. Linux exposes no desktop-neutral settings screen, so the call returns
   * `false`.
   */
  override fun openApplicationSettings(): Boolean = openLocationScreen()

  override val canOpenLocationServicesSettings: Boolean = command != null

  /** Opens the same screen as [openApplicationSettings], which also holds the services toggle. */
  override fun openLocationServicesSettings(): Boolean = openLocationScreen()

  private fun openLocationScreen(): Boolean {
    val command = command ?: return false
    return try {
      ProcessBuilder(command).start()
      true
    } catch (_: IOException) {
      false
    }
  }
}

@Composable
public actual fun rememberSystemSettingsLauncher(): SystemSettingsLauncher = remember {
  DesktopSystemSettingsLauncher()
}
