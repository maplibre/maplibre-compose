package org.maplibre.compose.location

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/** Opens the iOS settings screen related to location. */
public actual class SystemSettingsLauncher {
  public actual val canOpenApplicationSettings: Boolean = true

  /** Opens this application's page in the Settings app, where the user manages its permissions. */
  public actual fun openApplicationSettings(): Boolean {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return false
    val application = UIApplication.sharedApplication
    if (!application.canOpenURL(url)) return false
    application.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    return true
  }

  public actual val canOpenLocationServicesSettings: Boolean = false

  /** Returns `false`; iOS exposes no URL that opens the system Location Services screen. */
  public actual fun openLocationServicesSettings(): Boolean = false
}
