package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.maplibre.spatialk.geojson.Position

/**
 * A point whose screen location is preserved by
 * [org.maplibre.compose.map.MapState.animateCameraAround].
 */
@Immutable
public sealed interface CameraAnchor {
  /** A point in dp from the full map's top-left corner, including its camera padding. */
  @Immutable
  public data class Screen(public val point: DpOffset) : CameraAnchor {
    init {
      require(point.x.value.isFinite() && point.y.value.isFinite()) { "The anchor must be finite" }
    }
  }

  /** A location in the world copy nearest the camera center. Altitude is ignored. */
  @Immutable
  public data class Geographic(public val position: Position) : CameraAnchor {
    init {
      require(position.longitude.isFinite() && position.latitude.isFinite()) {
        "The anchor must be finite"
      }
      require(position.latitude in -85.0511287798066..85.0511287798066) {
        "The anchor must be within Mercator latitudes"
      }
    }
  }
}

/** Resolve on the engine thread, using one live transform for both conversions. */
internal fun CameraAnchor.resolveScreenPoint(
  size: DpSize,
  centerLongitude: Double,
  project: (Position) -> DpOffset,
  unproject: (DpOffset) -> Position,
): DpOffset {
  val point =
    when (this) {
      is CameraAnchor.Screen -> point
      is CameraAnchor.Geographic -> project(position)
    }
  require(
    point.x.value.isFinite() &&
      point.y.value.isFinite() &&
      point.x >= 0.dp &&
      point.y >= 0.dp &&
      point.x <= size.width &&
      point.y <= size.height
  ) {
    "The anchor must be inside the map viewport"
  }
  val location = unproject(point)
  require(location.longitude.isFinite() && location.latitude.isFinite()) {
    "The anchor must project onto the map"
  }
  // A point above the horizon can unproject to a clamped ground location. Native projects
  // geographic positions into the nearest world copy; comparing that copy with a screen anchor
  // in another visible copy would reject valid points, particularly on rotated, wide maps.
  if (abs(location.longitude - centerLongitude) <= 180.0) {
    val roundTrip = project(location)
    require(
      abs(roundTrip.x.value - point.x.value) < 0.5f && abs(roundTrip.y.value - point.y.value) < 0.5f
    ) {
      "The anchor must project onto the map"
    }
  }
  return point
}
