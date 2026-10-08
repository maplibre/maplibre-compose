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
     * Approximate lowest zoom used to shape the path. Native fits the flight toward this value,
     * even if the natural path stays above it. This is not a hard limit. Null uses the engine's
     * path.
     */
    public val minZoom: Double?,
  ) : CameraAnimation {
    private constructor(
      builder: Builder
    ) : this(
      builder.duration,
      builder.speed,
      builder.easing,
      builder.minZoom,
    )

    public actual constructor(
      from: Fly,
      block: Builder.() -> Unit,
    ) : this(Builder(from).apply(block))

    init {
      require(speed > 0.0) { "Flight speed must be positive: $speed" }
    }

    @MapOptionsDsl
    public actual class Builder internal actual constructor(from: Fly?) {
      public actual var duration: Duration? = from?.duration
      public actual var speed: Double = from?.speed ?: DefaultSpeed
      public actual var easing: CubicBezier = from?.easing ?: CubicBezier.Default
      /** See [Fly.minZoom]. */
      public var minZoom: Double? = from?.minZoom
    }

    public actual companion object {
      public actual const val DefaultSpeed: Double = 1.2 * 1.42
      public actual val Standard: Fly = Fly(Builder(from = null))
    }
  }
}
