package org.maplibre.compose.style

import android.provider.Settings
import org.maplibre.compose.mlnffi.AndroidContextProvider

/**
 * Reads the system animator duration scale from the current settings. Falls back to 1f when the
 * setting cannot be read: the context provider is uninitialized, the manifest was not merged, or
 * the host is a JVM unit test stub.
 */
internal actual fun systemAnimatorDurationScale(): Float = runCatching {
  Settings.Global.getFloat(
    AndroidContextProvider.context.contentResolver,
    Settings.Global.ANIMATOR_DURATION_SCALE,
    1f,
  )
}
  .getOrDefault(1f)
