package org.maplibre.compose.location

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The window that XDG portals use to parent system dialogs, such as the Linux location permission
 * prompt.
 *
 * [ProvideMapPresentationHost][org.maplibre.compose.desktop.ProvideMapPresentationHost] installs
 * the map host's window. Defaults to null, which asks the portal to present its dialog without a
 * parent.
 */
internal val LocalXdgPortalWindow: ProvidableCompositionLocal<XdgPortalWindow?> =
  staticCompositionLocalOf {
    null
  }
