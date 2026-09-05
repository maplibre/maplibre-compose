package org.maplibre.compose.style

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import org.maplibre.compose.mlnffi.AndroidContextProvider

/**
 * Follows the system animator duration scale through a content observer, registered once per
 * process on first read. Holds 1f when the setting cannot be read: the context provider is
 * uninitialized, the manifest was not merged, or the host is a JVM unit test stub.
 */
internal actual val systemAnimatorDurationScaleState: State<Float> by lazy {
  val state = mutableFloatStateOf(readAnimatorDurationScale())
  runCatching {
    val observer =
      object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
          state.floatValue = readAnimatorDurationScale()
        }
      }
    AndroidContextProvider.context.contentResolver.registerContentObserver(
      Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
      false,
      observer,
    )
  }
  state
}

private fun readAnimatorDurationScale(): Float = runCatching {
  Settings.Global.getFloat(
    AndroidContextProvider.context.contentResolver,
    Settings.Global.ANIMATOR_DURATION_SCALE,
    1f,
  )
}
  .getOrDefault(1f)
