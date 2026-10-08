package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.map.MapOptionsDsl
import org.maplibre.compose.util.formatToString

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
   *
   * [Standard] takes 300 milliseconds and uses [CubicBezier.Default].
   */
  @Immutable
  public class Ease private constructor(builder: Builder) : CameraAnimation {
    public val duration: Duration = builder.duration
    override val easing: CubicBezier = builder.easing

    /** Edits [from]; omitted settings inherit. */
    public constructor(
      from: Ease = Standard,
      block: Builder.() -> Unit,
    ) : this(Builder(from).apply(block))

    override fun equals(other: Any?): Boolean =
      other is Ease && duration == other.duration && easing == other.easing

    override fun hashCode(): Int = 31 * duration.hashCode() + easing.hashCode()

    override fun toString(): String =
      formatToString("Ease", "duration" to duration, "easing" to easing)

    @MapOptionsDsl
    public class Builder internal constructor(from: Ease?) {
      /** See [Ease.duration]. */
      public var duration: Duration = from?.duration ?: 300.milliseconds
      /** See [Ease.easing]. */
      public var easing: CubicBezier = from?.easing ?: CubicBezier.Default
    }

    public companion object {
      /** The default direct camera transition. */
      public val Standard: Ease = Ease(Builder(from = null))
    }
  }

  /**
   * Follows a flight path: the camera zooms out, crosses the ground, and zooms back in, so the map
   * remains legible over any distance.
   *
   * The flight takes [duration] when one is given. Otherwise its duration follows from the length
   * of the path and [speed]. When both are given, [duration] takes precedence.
   *
   * @property duration The total time of the flight. Null derives it from [speed].
   * @property speed The average speed in screenfuls per second, where a screenful is the visible
   *   span of the map. Defaults to [DefaultSpeed]. Must be positive. Ignored when [duration] is
   *   set.
   * @property minZoom Approximate lowest zoom used to shape the flight, not a hard limit. Native
   *   fits the path toward this value, even if it would naturally stay above it. GL JS only limits
   *   zooming out and also uses the map's minimum zoom to shape the path. Both engines apply the
   *   map's zoom constraints to the displayed camera. Null uses the engine's default path.
   * @throws IllegalArgumentException if [speed] is not positive.
   */
  @Immutable
  public class Fly private constructor(builder: Builder) : CameraAnimation {
    public val duration: Duration? = builder.duration
    public val speed: Double = builder.speed
    public val minZoom: Double? = builder.minZoom
    override val easing: CubicBezier = builder.easing

    /** Edits [from]; omitted settings inherit. */
    public constructor(
      from: Fly = Standard,
      block: Builder.() -> Unit,
    ) : this(Builder(from).apply(block))

    init {
      require(speed > 0.0) { "Flight speed must be positive: $speed" }
    }

    override fun equals(other: Any?): Boolean =
      other is Fly &&
        duration == other.duration &&
        speed == other.speed &&
        minZoom == other.minZoom &&
        easing == other.easing

    override fun hashCode(): Int {
      var result = duration?.hashCode() ?: 0
      result = 31 * result + speed.hashCode()
      result = 31 * result + (minZoom?.hashCode() ?: 0)
      return 31 * result + easing.hashCode()
    }

    override fun toString(): String =
      formatToString(
        "Fly",
        "duration" to duration,
        "speed" to speed,
        "minZoom" to minZoom,
        "easing" to easing,
      )

    @MapOptionsDsl
    public class Builder internal constructor(from: Fly?) {
      /** See [Fly.duration]. */
      public var duration: Duration? = from?.duration
      /** See [Fly.speed]. */
      public var speed: Double = from?.speed ?: DefaultSpeed
      /** See [Fly.minZoom]. */
      public var minZoom: Double? = from?.minZoom
      /** See [Fly.easing]. */
      public var easing: CubicBezier = from?.easing ?: CubicBezier.Default
    }

    public companion object {
      /** The default flight speed, in screenfuls per second. */
      public const val DefaultSpeed: Double = 1.2 * 1.42

      /** The default flight transition. */
      public val Standard: Fly = Fly(Builder(from = null))
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
