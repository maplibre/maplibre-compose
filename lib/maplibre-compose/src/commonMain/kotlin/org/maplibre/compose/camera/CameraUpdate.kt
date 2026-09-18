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
  public val target: Position? = null,
  public val zoom: Double? = null,
  public val bearing: Double? = null,
  public val tilt: Double? = null,
  public val padding: DpPadding? = null,
) {
  init {
    require(target != null || zoom != null || bearing != null || tilt != null || padding != null) {
      "A camera update must specify at least one property"
    }
    require(target == null || target.longitude.isFinite() && target.latitude.isFinite()) {
      "Target coordinates must be finite"
    }
    require(zoom == null || zoom.isFinite()) { "Zoom must be finite" }
    require(bearing == null || bearing.isFinite()) { "Bearing must be finite" }
    require(tilt == null || tilt.isFinite()) { "Tilt must be finite" }
    require(
      padding == null ||
        listOf(padding.left, padding.top, padding.right, padding.bottom).all {
          it.value.isFinite() && it.value >= 0f
        }
    ) {
      "Padding must be finite and nonnegative"
    }
  }

  internal fun applyTo(position: CameraPosition): CameraPosition =
    position.copy(
      target = target ?: position.target,
      zoom = zoom ?: position.zoom,
      bearing = bearing ?: position.bearing,
      tilt = tilt ?: position.tilt,
      padding = padding ?: position.padding,
    )
}
