package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the camera moves from its current position to a new one.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 *
 * [Ease] travels directly. [Fly] zooms out, travels, and zooms back in. MapLibre Native and
 * MapLibre GL JS each implement both transitions with the same controls. Their paths and timing are
 * engine-dependent.
 */
@Immutable
public sealed interface CameraAnimation {
  /** The timing curve of the transition. */
  public val easing: CubicBezier

  /**
   * Moves the camera directly from its current position to the new position over [duration]. Zoom
   * interpolates straight to its target, so the transition never zooms out on the way, and an ease
   * between distant positions crosses the ground quickly at the current zoom. Use it for short
   * moves, such as following a location or changing zoom in place.
   */
  @Immutable
  public data class Ease(
    val duration: Duration = 300.milliseconds,
    override val easing: CubicBezier = CubicBezier.Default,
  ) : CameraAnimation

  /**
   * Follows a flight path: the camera zooms out, crosses the ground, and zooms back in, so the map
   * remains legible over any distance.
   *
   * The flight takes [duration] when one is given. Otherwise its duration follows from the length
   * of the path and [speed]. When both are given, [duration] takes precedence.
   *
   * @param duration The total time of the flight. Null derives it from [speed].
   * @param speed The average speed in screenfuls per second, where a screenful is the visible span
   *   of the map. Defaults to [DefaultSpeed]. Must be positive. Ignored when [duration] is set.
   * @param minZoom Approximate lowest zoom used to shape the flight, not a hard limit. Native fits
   *   the path toward this value, even if it would naturally stay above it. GL JS only limits
   *   zooming out and also uses the map's minimum zoom to shape the path. Both engines apply the
   *   map's zoom constraints to the displayed camera. Null uses the engine's default path.
   */
  @Immutable
  public data class Fly(
    val duration: Duration? = null,
    val speed: Double = DefaultSpeed,
    val minZoom: Double? = null,
    override val easing: CubicBezier = CubicBezier.Default,
  ) : CameraAnimation {
    init {
      require(speed > 0.0) { "Flight speed must be positive: $speed" }
    }

    public companion object {
      /** The default flight speed, in screenfuls per second. */
      public const val DefaultSpeed: Double = 1.2 * 1.42
    }
  }
}

/**
 * A cubic bezier timing curve from (0, 0) to (1, 1) with control points ([x1], [y1]) and ([x2],
 * [y2]). [x1] and [x2] must lie in `[0, 1]` so that every time maps to one progress value.
 */
@Immutable
public data class CubicBezier(
  val x1: Double,
  val y1: Double,
  val x2: Double,
  val y2: Double,
) {
  init {
    require(x1 in 0.0..1.0 && x2 in 0.0..1.0) {
      "Bezier control point x values must lie in [0, 1]: $x1, $x2"
    }
  }

  public companion object {
    /** The CSS `ease` curve: control points (0.25, 0.1) and (0.25, 1). */
    public val Default: CubicBezier = CubicBezier(0.25, 0.1, 0.25, 1.0)

    /** A constant rate of change. */
    public val Linear: CubicBezier = CubicBezier(0.0, 0.0, 1.0, 1.0)
  }
}
