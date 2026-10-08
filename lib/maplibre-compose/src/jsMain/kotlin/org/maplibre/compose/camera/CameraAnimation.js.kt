package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.map.MapOptionsDsl

@Immutable
public actual sealed interface CameraAnimation {
  public actual val easing: CubicBezier

  @Immutable
  public actual data class Ease
  private constructor(
    public actual val duration: Duration,
    public actual override val easing: CubicBezier,
  ) : CameraAnimation {
    public actual constructor(
      from: Ease,
      block: Builder.() -> Unit,
    ) : this(Builder(from).apply(block))

    private constructor(builder: Builder) : this(builder.duration, builder.easing)

    @MapOptionsDsl
    public actual class Builder internal actual constructor(from: Ease?) {
      public actual var duration: Duration = from?.duration ?: 300.milliseconds
      public actual var easing: CubicBezier = from?.easing ?: CubicBezier.Default
    }

    public actual companion object {
      public actual val Standard: Ease = Ease(Builder(null))
    }
  }

  @Immutable
  public actual data class Fly
  private constructor(
    public actual val duration: Duration?,
    public actual val speed: Double,
    public actual override val easing: CubicBezier,
    /**
     * The lowest zoom the flight may reach. A natural arc above it is unchanged. This also limits
     * zooming out when [curve] is set. Null uses the map's minimum zoom.
     */
    public val minZoom: Double?,
    /**
     * The flight curve. Higher values zoom out more; lower values approach an ease. Defaults to
     * [DefaultCurve]. Must be positive.
     */
    public val curve: Double,
    /**
     * Speed in screenfuls per second for linear timing. When set, this replaces [speed] for a
     * flight without [duration]. Must be positive. Null uses [speed].
     */
    public val screenSpeed: Double?,
    /**
     * Maximum flight time. If the calculated or specified [duration] exceeds it, the flight is
     * immediate. Must be positive. Null has no maximum.
     */
    public val maxDuration: Duration?,
  ) : CameraAnimation {
    private constructor(
      builder: Builder
    ) : this(
      builder.duration,
      builder.speed,
      builder.easing,
      builder.minZoom,
      builder.curve,
      builder.screenSpeed,
      builder.maxDuration,
    )

    public actual constructor(
      from: Fly,
      block: Builder.() -> Unit,
    ) : this(Builder(from).apply(block))

    init {
      require(speed > 0.0) { "Flight speed must be positive: $speed" }
      require(curve > 0.0) { "Flight curve must be positive: $curve" }
      require(screenSpeed == null || screenSpeed > 0.0) {
        "Flight screen speed must be positive: $screenSpeed"
      }
      require(maxDuration == null || maxDuration.isPositive()) {
        "Flight maximum duration must be positive: $maxDuration"
      }
    }

    @MapOptionsDsl
    public actual class Builder internal actual constructor(from: Fly?) {
      public actual var duration: Duration? = from?.duration
      public actual var speed: Double = from?.speed ?: DefaultSpeed
      public actual var easing: CubicBezier = from?.easing ?: CubicBezier.Default
      /** See [Fly.minZoom]. */
      public var minZoom: Double? = from?.minZoom
      /** See [Fly.curve]. */
      public var curve: Double = from?.curve ?: DefaultCurve
      /** See [Fly.screenSpeed]. */
      public var screenSpeed: Double? = from?.screenSpeed
      /** See [Fly.maxDuration]. */
      public var maxDuration: Duration? = from?.maxDuration
    }

    public actual companion object {
      public actual const val DefaultSpeed: Double = 1.2 * 1.42
      /** The default flight curve used by MapLibre GL JS. */
      public const val DefaultCurve: Double = 1.42
      public actual val Standard: Fly = Fly(Builder(from = null))
    }
  }
}
