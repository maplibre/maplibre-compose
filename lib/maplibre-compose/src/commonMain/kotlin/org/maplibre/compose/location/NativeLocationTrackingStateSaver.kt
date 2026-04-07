package org.maplibre.compose.location

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope

internal object NativeLocationTrackingStateSaver : Saver<NativeLocationTrackingState, String> {
  override fun SaverScope.save(value: NativeLocationTrackingState): String {
    return value.trackingMode.name
  }

  override fun restore(value: String): NativeLocationTrackingState {
    return NativeLocationTrackingState(UserTrackingMode.valueOf(value))
  }
}
