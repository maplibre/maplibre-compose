package org.maplibre.compose.style

import android.provider.Settings
import org.maplibre.compose.mlnffi.AndroidContextProvider

/**
 * Reads the system animator duration scale live, so a developer-options change applies to the next
 * transition without an app restart. Falls back to 1f when the setting cannot be read: the context
 * provider is uninitialized, the manifest was not merged, or the host is a JVM unit test stub.
 */
internal actual fun animatorDurationScale(): Float = runCatching {
  Settings.Global.getFloat(
    AndroidContextProvider.context.contentResolver,
    Settings.Global.ANIMATOR_DURATION_SCALE,
    1f,
  )
}
  .getOrDefault(1f)
