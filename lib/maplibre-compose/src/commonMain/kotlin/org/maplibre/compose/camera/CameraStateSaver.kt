package org.maplibre.compose.camera

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.maplibre.spatialk.geojson.Position

internal object CameraStateSaver : Saver<CameraState, Map<String, Double>> {
  override fun SaverScope.save(value: CameraState): Map<String, Double> {
    val position = value.position
    return mapOf(
      Keys.BEARING to position.bearing,
      Keys.LATITUDE to position.target.latitude,
      Keys.LONGITUDE to position.target.longitude,
      Keys.TILT to position.tilt,
      Keys.ZOOM to position.zoom,
      Keys.PADDING_LEFT to
        position.padding.calculateStartPadding(LayoutDirection.Ltr).value.toDouble(),
      Keys.PADDING_TOP to position.padding.calculateTopPadding().value.toDouble(),
      Keys.PADDING_RIGHT to
        position.padding.calculateEndPadding(LayoutDirection.Ltr).value.toDouble(),
      Keys.PADDING_BOTTOM to position.padding.calculateBottomPadding().value.toDouble(),
    )
  }

  override fun restore(value: Map<String, Double>): CameraState {
    return CameraState(
      CameraPosition(
        bearing = value[Keys.BEARING]!!,
        target = Position(latitude = value[Keys.LATITUDE]!!, longitude = value[Keys.LONGITUDE]!!),
        tilt = value[Keys.TILT]!!,
        zoom = value[Keys.ZOOM]!!,
        padding =
          PaddingValues.Absolute(
            left = value[Keys.PADDING_LEFT]!!.dp,
            top = value[Keys.PADDING_TOP]!!.dp,
            right = value[Keys.PADDING_RIGHT]!!.dp,
            bottom = value[Keys.PADDING_BOTTOM]!!.dp,
          ),
      )
    )
  }

  private object Keys {
    const val BEARING = "bearing"
    const val LATITUDE = "latitude"
    const val LONGITUDE = "longitude"
    const val TILT = "tilt"
    const val ZOOM = "zoom"
    const val PADDING_LEFT = "padding_left"
    const val PADDING_TOP = "padding_top"
    const val PADDING_RIGHT = "padding_right"
    const val PADDING_BOTTOM = "padding_bottom"
  }
}
