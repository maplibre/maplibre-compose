package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import org.maplibre.nativeffi.map.TileLodMode as FfiTileLodMode
import org.maplibre.nativeffi.map.TileOptions

/** A MapLibre Native tile-detail algorithm and its tuning parameters. */
@Immutable
public abstract class TileLodAlgorithm private constructor() {
  internal abstract fun toFfi(): TileOptions

  /**
   * Keeps the finest tiles around the screen centre and uses coarser tiles farther away.
   *
   * @property minRadius Radius around the screen centre, in tile units, that keeps the finest zoom.
   *   Must be finite and at least 1.
   * @property scale Scale applied to distance from the screen centre. Must be finite and
   *   nonnegative. Values above 1 coarsen distant tiles; 0 keeps the finest zoom.
   * @property pitchThreshold Camera pitch in degrees from nadir, matching
   *   [org.maplibre.compose.camera.CameraPosition.pitch]. Distance-based coarsening runs when the
   *   pitch exceeds this value. Must be in `0..180`; 180 disables coarsening.
   * @property zoomShift Added to the covering zoom, including below [pitchThreshold]. Must be
   *   finite. A value of -1 requests roughly four times fewer tiles for the same view.
   */
  @Immutable
  public data class ScreenCenter
  internal constructor(
    public val minRadius: Double,
    public val scale: Double,
    public val pitchThreshold: Double,
    public val zoomShift: Double,
  ) : TileLodAlgorithm() {
    /** Edits [from]; without it, uses Native's defaults. Omitted settings inherit. */
    public constructor(
      from: ScreenCenter? = null,
      block: Builder.() -> Unit = {},
    ) : this(Builder(from).apply(block))

    private constructor(
      builder: Builder
    ) : this(builder.minRadius, builder.scale, builder.pitchThreshold, builder.zoomShift)

    init {
      require(minRadius.isFinite() && minRadius >= 1.0) {
        "minRadius must be finite and at least 1, was $minRadius"
      }
      validate(scale, pitchThreshold, zoomShift)
    }

    @MapOptionsDsl
    public class Builder internal constructor(from: ScreenCenter?) {
      /** See [ScreenCenter.minRadius]. */
      public var minRadius: Double = from?.minRadius ?: 3.0

      /** See [ScreenCenter.scale]. */
      public var scale: Double = from?.scale ?: 1.0

      /** See [ScreenCenter.pitchThreshold]. */
      public var pitchThreshold: Double = from?.pitchThreshold ?: 60.0

      /** See [ScreenCenter.zoomShift]. */
      public var zoomShift: Double = from?.zoomShift ?: 0.0
    }

    internal override fun toFfi(): TileOptions =
      tileOptions(FfiTileLodMode.DEFAULT, minRadius, scale, pitchThreshold, zoomShift)
  }

  /**
   * Keeps finer tiles nearest the camera, with coarser tiles toward the horizon.
   *
   * Unlike [ScreenCenter], this algorithm can request tiles above the covering zoom when the pitch
   * exceeds [pitchThreshold], up to the source's maximum zoom.
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
  public data class CameraDistance
  internal constructor(
    public val scale: Double,
    public val pitchThreshold: Double,
    public val zoomShift: Double,
  ) : TileLodAlgorithm() {
    /** Edits [from]; without it, uses Native's defaults. Omitted settings inherit. */
    public constructor(
      from: CameraDistance? = null,
      block: Builder.() -> Unit = {},
    ) : this(Builder(from).apply(block))

    private constructor(
      builder: Builder
    ) : this(builder.scale, builder.pitchThreshold, builder.zoomShift)

    init {
      validate(scale, pitchThreshold, zoomShift)
    }

    @MapOptionsDsl
    public class Builder internal constructor(from: CameraDistance?) {
      /** See [CameraDistance.scale]. */
      public var scale: Double = from?.scale ?: 1.0

      /** See [CameraDistance.pitchThreshold]. */
      public var pitchThreshold: Double = from?.pitchThreshold ?: 60.0

      /** See [CameraDistance.zoomShift]. */
      public var zoomShift: Double = from?.zoomShift ?: 0.0
    }

    internal override fun toFfi(): TileOptions =
      tileOptions(FfiTileLodMode.DISTANCE, 3.0, scale, pitchThreshold, zoomShift)
  }
}

private fun validate(scale: Double, pitchThreshold: Double, zoomShift: Double) {
  require(scale.isFinite() && scale >= 0.0) { "scale must be finite and nonnegative, was $scale" }
  require(pitchThreshold in 0.0..180.0) {
    "pitchThreshold must be in [0, 180] degrees, was $pitchThreshold"
  }
  require(zoomShift.isFinite()) { "zoomShift must be finite, was $zoomShift" }
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
