package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.Position

/**
 * Camera properties to change. Null properties are omitted, including [padding], which excludes
 * viewport insets. At least one property must be specified. See
 * [org.maplibre.compose.map.MapState.animateCamera] for animation, replacement, and platform
 * behavior.
 */
@Immutable
@Serializable
public data class CameraUpdate(
  public val center: Position? = null,
  public val zoom: Double? = null,
  public val bearing: Double? = null,
  public val pitch: Double? = null,
  public val padding: DpPadding? = null,
) {
  init {
    require(center != null || zoom != null || bearing != null || pitch != null || padding != null) {
      "A camera update must specify at least one property"
    }
    require(center == null || center.longitude.isFinite() && center.latitude.isFinite()) {
      "Center coordinates must be finite, was $center"
    }
    require(zoom == null || zoom.isFinite()) { "Zoom must be finite, was $zoom" }
    require(bearing == null || bearing.isFinite()) { "Bearing must be finite, was $bearing" }
    require(pitch == null || pitch.isFinite()) { "Pitch must be finite, was $pitch" }
    require(
      padding == null ||
        listOf(padding.left, padding.top, padding.right, padding.bottom).all {
          it.value.isFinite() && it.value >= 0f
        }
    ) {
      "Padding must be finite and nonnegative, was $padding"
    }
  }

  internal fun applyTo(position: CameraPosition): CameraPosition =
    position.copy(
      center = center ?: position.center,
      zoom = zoom ?: position.zoom,
      bearing = bearing ?: position.bearing,
      pitch = pitch ?: position.pitch,
      padding = padding ?: position.padding,
    )
}
