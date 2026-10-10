package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * Limits the camera position that the map can display.
 *
 * The map applies these limits to gestures and camera commands. A command that targets a camera
 * outside the limits moves the camera only as far as the limits allow. When new limits exclude the
 * current camera, the map moves the camera inside them.
 *
 * @property minZoom Minimum camera zoom. Defaults to 0.
 * @property maxZoom Maximum camera zoom. Defaults to 20.
 * @property minPitch Minimum camera pitch in degrees. Defaults to 0.
 * @property maxPitch Maximum camera pitch in degrees. Defaults to 60.
 * @property boundingBox Geographic bounds for the camera, or `null` for no bounds. On MapLibre
 *   Native (Android, iOS, and desktop), the camera center stays inside the bounds, so the map can
 *   show area outside them near its edges. In the browser, the visible area stays inside the
 *   bounds, and the map zooms in when the bounds are smaller than the viewport. A box that crosses
 *   the antimeridian may use either GeoJSON form: an east longitude less than the west, or an east
 *   longitude past 180°.
 */
@Immutable
public data class CameraConstraints
private constructor(
  public val minZoom: Double,
  public val maxZoom: Double,
  public val minPitch: Double,
  public val maxPitch: Double,
  public val boundingBox: BoundingBox?,
) {
  private constructor(
    builder: Builder
  ) : this(
    builder.minZoom,
    builder.maxZoom,
    builder.minPitch,
    builder.maxPitch,
    builder.boundingBox,
  )

  /** Edits [from]; omitted constraints inherit. */
  public constructor(
    from: CameraConstraints = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  @MapOptionsDsl
  public class Builder internal constructor(from: CameraConstraints?) {
    /** See [CameraConstraints.minZoom]. */
    public var minZoom: Double = from?.minZoom ?: 0.0
    /** See [CameraConstraints.maxZoom]. */
    public var maxZoom: Double = from?.maxZoom ?: 20.0
    /** See [CameraConstraints.minPitch]. */
    public var minPitch: Double = from?.minPitch ?: 0.0
    /** See [CameraConstraints.maxPitch]. */
    public var maxPitch: Double = from?.maxPitch ?: 60.0
    /** See [CameraConstraints.boundingBox]. */
    public var boundingBox: BoundingBox? = from?.boundingBox
  }

  public companion object {
    /** The default camera limits. */
    public val Standard: CameraConstraints = CameraConstraints(Builder(from = null))
  }
}
