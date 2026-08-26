package org.maplibre.compose.camera

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import org.maplibre.spatialk.geojson.Position

/** Saves and restores a [CameraPosition] with `rememberSaveable`, for `rememberMapState`. */
internal object CameraPositionSaver : Saver<CameraPosition, Map<String, Double>> {
  override fun SaverScope.save(value: CameraPosition): Map<String, Double> {
    return mapOf(
      Keys.BEARING to value.bearing,
      Keys.LATITUDE to value.target.latitude,
      Keys.LONGITUDE to value.target.longitude,
      Keys.TILT to value.tilt,
      Keys.ZOOM to value.zoom,
    )
  }

  override fun restore(value: Map<String, Double>): CameraPosition {
    return CameraPosition(
      bearing = value[Keys.BEARING]!!,
      target = Position(latitude = value[Keys.LATITUDE]!!, longitude = value[Keys.LONGITUDE]!!),
      tilt = value[Keys.TILT]!!,
      zoom = value[Keys.ZOOM]!!,
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
