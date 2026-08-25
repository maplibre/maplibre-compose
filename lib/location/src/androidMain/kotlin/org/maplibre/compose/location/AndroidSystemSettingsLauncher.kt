package org.maplibre.compose.location

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens Android settings screens related to location.
 *
 * @param context Any [Context]; a context that cannot reach an activity launches the screen in a
 *   new task.
 */
public class AndroidSystemSettingsLauncher(private val context: Context) : SystemSettingsLauncher {
  override val canOpenApplicationSettings: Boolean = true

  /**
   * Opens this application's details screen in the system settings, where the user manages its
   * permissions.
   */
  override fun openApplicationSettings(): Boolean =
    launch(
      Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
      )
    )

  override val canOpenLocationServicesSettings: Boolean = true

  /** Opens the location settings screen, where the user turns location services on. */
  override fun openLocationServicesSettings(): Boolean =
    launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

  private fun launch(intent: Intent): Boolean {
    if (context.findActivityOrNull() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      context.startActivity(intent)
      true
    } catch (_: ActivityNotFoundException) {
      false
    }
  }
}
