package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import org.maplibre.nativeffi.map.TileLodMode as FfiTileLodMode
import org.maplibre.nativeffi.map.TileOptions

/**
 * A MapLibre Native tile-detail algorithm and its tuning parameters.
 *
 * Available on Android, iOS, and desktop. Assign an algorithm to
 * [TileLodOptions.Builder.algorithm]. Instances have value equality. Only the library can define
 * algorithms; future releases may add algorithms, so callers inspecting this type must handle other
 * implementations.
 */
@Immutable
public abstract class TileLodAlgorithm private constructor() {
  internal abstract fun toFfi(): TileOptions

  /**
   * Keeps the finest tiles around the screen centre and uses coarser tiles farther away.
   *
   * @property minRadius Radius around the view point, in tile units, that keeps the finest zoom.
   *   Must be finite and at least 1.
   * @property scale Scale applied to distance from the view point. Must be finite and nonnegative.
   *   Values above 1 coarsen distant tiles; 0 keeps the finest zoom.
   * @property pitchThreshold Camera pitch in degrees from nadir, matching
   *   [org.maplibre.compose.camera.CameraPosition.pitch]. Distance-based coarsening runs when the
   *   pitch exceeds this value. Must be in `0..180`; 180 disables coarsening.
   * @property zoomShift Added to the covering zoom, including below [pitchThreshold]. Must be
   *   finite. A value of -1 requests roughly four times fewer tiles for the same view.
   */
  @Immutable
  public class Default private constructor(private val parameters: DefaultTileLodParameters) :
    TileLodAlgorithm() {
    /** Edits [from]; without it, uses Native's defaults. Omitted settings inherit. */
    public constructor(
      from: Default? = null,
      block: Builder.() -> Unit = {},
    ) : this(Builder(from).apply(block).build())

    public val minRadius: Double
      get() = parameters.minRadius

    public val scale: Double
      get() = parameters.scale

    public val pitchThreshold: Double
      get() = parameters.pitchThreshold

    public val zoomShift: Double
      get() = parameters.zoomShift

    override fun equals(other: Any?): Boolean = other is Default && parameters == other.parameters

    override fun hashCode(): Int = parameters.hashCode()

    @MapOptionsDsl
    public class Builder internal constructor(from: Default?) {
      /** See [Default.minRadius]. */
      public var minRadius: Double = from?.minRadius ?: 3.0

      /** See [Default.scale]. */
      public var scale: Double = from?.scale ?: 1.0

      /** See [Default.pitchThreshold]. */
      public var pitchThreshold: Double = from?.pitchThreshold ?: 60.0

      /** See [Default.zoomShift]. */
      public var zoomShift: Double = from?.zoomShift ?: 0.0

      internal fun build(): DefaultTileLodParameters {
        require(minRadius.isFinite() && minRadius >= 1.0) {
          "minRadius must be finite and at least 1"
        }
        validate(scale, pitchThreshold, zoomShift)
        return DefaultTileLodParameters(minRadius, scale, pitchThreshold, zoomShift)
      }
    }

    internal override fun toFfi(): TileOptions =
      tileOptions(FfiTileLodMode.DEFAULT, minRadius, scale, pitchThreshold, zoomShift)
  }

  /**
   * Keeps finer tiles nearest the camera, with coarser tiles toward the horizon.
   *
   * Unlike [Default], this algorithm can request tiles above the covering zoom when the pitch
   * exceeds [pitchThreshold], up to the source's maximum zoom. It has no minimum-radius setting.
   *
   * @property scale Scale applied to camera-to-tile distance. Must be finite and nonnegative.
   *   Values above 1 coarsen distant tiles; 0 keeps the finest zoom permitted by the source and
   *   [pitchThreshold].
   * @property pitchThreshold Camera pitch in degrees from nadir, matching
   *   [org.maplibre.compose.camera.CameraPosition.pitch]. Variable tile zoom runs when the pitch
   *   exceeds this value. Must be in `0..180`; 180 disables variable tile zoom.
   * @property zoomShift Added to the covering zoom, including below [pitchThreshold]. Must be
   *   finite. The covering zoom also affects distance-based tile selection above [pitchThreshold].
   */
  @Immutable
  public class Distance private constructor(private val parameters: DistanceTileLodParameters) :
    TileLodAlgorithm() {
    /** Edits [from]; without it, uses Native's defaults. Omitted settings inherit. */
    public constructor(
      from: Distance? = null,
      block: Builder.() -> Unit = {},
    ) : this(Builder(from).apply(block).build())

    public val scale: Double
      get() = parameters.scale

    public val pitchThreshold: Double
      get() = parameters.pitchThreshold

    public val zoomShift: Double
      get() = parameters.zoomShift

    override fun equals(other: Any?): Boolean = other is Distance && parameters == other.parameters

    override fun hashCode(): Int = parameters.hashCode()

    @MapOptionsDsl
    public class Builder internal constructor(from: Distance?) {
      /** See [Distance.scale]. */
      public var scale: Double = from?.scale ?: 1.0

      /** See [Distance.pitchThreshold]. */
      public var pitchThreshold: Double = from?.pitchThreshold ?: 60.0

      /** See [Distance.zoomShift]. */
      public var zoomShift: Double = from?.zoomShift ?: 0.0

      internal fun build(): DistanceTileLodParameters {
        validate(scale, pitchThreshold, zoomShift)
        return DistanceTileLodParameters(scale, pitchThreshold, zoomShift)
      }
    }

    internal override fun toFfi(): TileOptions =
      tileOptions(FfiTileLodMode.DISTANCE, 3.0, scale, pitchThreshold, zoomShift)
  }
}

internal data class DefaultTileLodParameters(
  val minRadius: Double,
  val scale: Double,
  val pitchThreshold: Double,
  val zoomShift: Double,
)

internal data class DistanceTileLodParameters(
  val scale: Double,
  val pitchThreshold: Double,
  val zoomShift: Double,
)

private fun validate(scale: Double, pitchThreshold: Double, zoomShift: Double) {
  require(scale.isFinite() && scale >= 0.0) { "scale must be finite and nonnegative" }
  require(pitchThreshold in 0.0..180.0) { "pitchThreshold must be in [0, 180] degrees" }
  require(zoomShift.isFinite()) { "zoomShift must be finite" }
}

private fun tileOptions(
  mode: FfiTileLodMode,
  minRadius: Double,
  scale: Double,
  pitchThreshold: Double,
  zoomShift: Double,
): TileOptions =
  TileOptions().also {
    it.lodMode = mode
    it.lodMinRadius = minRadius
    it.lodScale = scale
    it.lodPitchThreshold = pitchThreshold * PI / 180.0
    it.lodZoomShift = zoomShift
  }
