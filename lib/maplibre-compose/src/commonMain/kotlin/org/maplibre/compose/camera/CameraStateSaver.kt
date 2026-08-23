package org.maplibre.compose.camera

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import org.maplibre.spatialk.geojson.Position

/** Saves and restores a [CameraState] with `rememberSaveable`. */
public object CameraStateSaver : Saver<CameraState, Map<String, Double>> {
  override fun SaverScope.save(value: CameraState): Map<String, Double> {
    val position = value.position
    return mapOf(
      Keys.BEARING to position.bearing,
      Keys.LATITUDE to position.target.latitude,
      Keys.LONGITUDE to position.target.longitude,
      Keys.TILT to position.tilt,
      Keys.ZOOM to position.zoom,
    )
  }

  override fun restore(value: Map<String, Double>): CameraState {
    return CameraState(
      CameraPosition(
        bearing = value[Keys.BEARING]!!,
        target = Position(latitude = value[Keys.LATITUDE]!!, longitude = value[Keys.LONGITUDE]!!),
        tilt = value[Keys.TILT]!!,
        zoom = value[Keys.ZOOM]!!,
      )
    )
  }

  private object Keys {
    const val BEARING = "bearing"
    const val LATITUDE = "latitude"
    const val LONGITUDE = "longitude"
    const val TILT = "tilt"
    const val ZOOM = "zoom"
  }
}
