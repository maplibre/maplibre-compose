package org.maplibre.compose.camera

import androidx.compose.runtime.mutableStateOf
import org.maplibre.compose.map.MapAdapter

/** Internal bridge between durable map state and one platform adapter. */
internal class CameraState(firstPosition: CameraPosition) {
  internal val positionState = mutableStateOf(firstPosition)

  internal var map: MapAdapter? = null
    set(value) {
      val previous = field
      field = value
      if (value !== previous && value != null) {
        value.setCameraPosition(positionState.value)
      }
    }
}
