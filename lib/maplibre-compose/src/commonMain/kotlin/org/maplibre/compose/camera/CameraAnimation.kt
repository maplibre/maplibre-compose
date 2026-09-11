package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.util.mercatorPixelDistance

/**
 * How the camera moves from its current position to a new one.
 *
 * [Ease] travels directly. [Fly] zooms out, travels, and zooms back in. MapLibre Native and
 * MapLibre GL JS each implement both transitions with the same controls. Their paths and timing are
 * close but not identical: the engines interpolate the center differently and GL JS measures the
 * viewport without camera padding.
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
   * of the path and [speed]. Set at most one of the two. A flight that changes only bearing or
   * tilt, or nothing, has no path to pace: without a duration it becomes an [Ease] with the default
   * duration and the same [easing].
   *
   * @param duration The total time of the flight. Null derives it from [speed].
   * @param speed The average speed in screenfuls per second, where a screenful is the visible span
   *   of the map. Null uses [DefaultSpeed]. Ignored when [duration] is set.
   * @param minZoom Keeps the flight path from zooming out past this zoom. The engines fit the
   *   flight curve so that its peak lands near this value rather than clamping, so the path can
   *   pass up to about half a zoom level below it. A value below the map's minimum zoom or below
   *   the natural path has no effect.
   */
  @Immutable
  public data class Fly(
    val duration: Duration? = null,
    val speed: Double? = null,
    val minZoom: Double? = null,
    override val easing: CubicBezier = CubicBezier.Default,
  ) : CameraAnimation {
    init {
      require(duration == null || speed == null) { "Set a flight duration or a speed, not both" }
      require(speed == null || speed > 0.0) { "Flight speed must be positive: $speed" }
    }

    public companion object {
      /** The flight speed when [speed] is null, in screenfuls per second. */
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

/**
 * Returns the animation to run from [from] to [to], where [to] has the zoom the map will apply. A
 * speed-paced flight between the same center and zoom has no path length to derive a duration from,
 * and the engines disagree about it: MapLibre Native jumps and MapLibre GL JS eases for its own
 * default duration. Both instead run an [CameraAnimation.Ease] with the default duration. The path
 * test is the engines' own: the projected distance at the current zoom, in pixels.
 */
internal fun CameraAnimation.forPath(from: CameraPosition, to: CameraPosition): CameraAnimation {
  if (this !is CameraAnimation.Fly || duration != null) return this
  val hasPath =
    abs(to.zoom - from.zoom) > PATH_ZOOM_EPSILON ||
      mercatorPixelDistance(from.zoom, from.target, to.target) > PATH_PIXEL_EPSILON
  return if (hasPath) this else CameraAnimation.Ease(easing = easing)
}

/** GL JS treats a shorter projected path as too short to fly; MapLibre Native uses half this. */
private const val PATH_PIXEL_EPSILON = 2e-6

private const val PATH_ZOOM_EPSILON = 1e-6
