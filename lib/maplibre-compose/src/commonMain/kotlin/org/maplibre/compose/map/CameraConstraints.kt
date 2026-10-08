package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import org.maplibre.compose.util.formatToString
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * Limits the camera position that the map can display.
 *
 * @property minZoom Minimum camera zoom. Defaults to 0.
 * @property maxZoom Maximum camera zoom. Defaults to 20.
 * @property minPitch Minimum camera pitch in degrees. Defaults to 0.
 * @property maxPitch Maximum camera pitch in degrees. Defaults to 60.
 * @property boundingBox Geographic bounds for the camera, or `null` for no bounds.
 */
@Immutable
public class CameraConstraints private constructor(builder: Builder) {
  public val minZoom: Double = builder.minZoom
  public val maxZoom: Double = builder.maxZoom
  public val minPitch: Double = builder.minPitch
  public val maxPitch: Double = builder.maxPitch
  public val boundingBox: BoundingBox? = builder.boundingBox

  /** Edits [from]; omitted constraints inherit. */
  public constructor(
    from: CameraConstraints = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  override fun equals(other: Any?): Boolean =
    other is CameraConstraints &&
      minZoom.compareTo(other.minZoom) == 0 &&
      maxZoom.compareTo(other.maxZoom) == 0 &&
      minPitch.compareTo(other.minPitch) == 0 &&
      maxPitch.compareTo(other.maxPitch) == 0 &&
      boundingBox == other.boundingBox

  override fun hashCode(): Int {
    var result = minZoom.hashCode()
    result = 31 * result + maxZoom.hashCode()
    result = 31 * result + minPitch.hashCode()
    result = 31 * result + maxPitch.hashCode()
    result = 31 * result + (boundingBox?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    formatToString(
      "CameraConstraints",
      "minZoom" to minZoom,
      "maxZoom" to maxZoom,
      "minPitch" to minPitch,
      "maxPitch" to maxPitch,
      "boundingBox" to boundingBox,
    )

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
