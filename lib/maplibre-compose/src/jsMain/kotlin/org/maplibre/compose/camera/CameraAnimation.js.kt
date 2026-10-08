package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import org.maplibre.compose.map.MapOptionsDsl
import org.maplibre.compose.util.formatToString

@Immutable
public actual sealed interface CameraAnimation {
  public actual val easing: CubicBezier

  @Immutable
  public actual class Ease private constructor(private val fields: EaseFields) : CameraAnimation {
    public actual val duration: Duration
      get() = fields.duration

    public actual override val easing: CubicBezier
      get() = fields.easing

    public actual constructor(
      from: Ease,
      block: Builder.() -> Unit,
    ) : this(Builder(from).apply(block).fields.build())

    actual override fun equals(other: Any?): Boolean = other is Ease && fields == other.fields

    actual override fun hashCode(): Int = fields.hashCode()

    actual override fun toString(): String = fields.toString()

    @MapOptionsDsl
    public actual class Builder internal actual constructor(from: Ease?) {
      internal val fields = EaseFields.Builder(from?.fields)
      public actual var duration: Duration
        get() = fields.duration
        set(value) {
          fields.duration = value
        }

      public actual var easing: CubicBezier
        get() = fields.easing
        set(value) {
          fields.easing = value
        }
    }

    public actual companion object {
      public actual val Standard: Ease = Ease(EaseFields.Builder(null).build())
    }
  }

  @Immutable
  public actual class Fly
  private constructor(
    private val fields: FlyFields,
    /**
     * The lowest zoom the flight may reach. A natural arc above it is unchanged. This also limits
     * zooming out when [curve] is set. Null uses the map's minimum zoom.
     */
    public val minZoom: Double?,
    /**
     * The flight curve. Higher values zoom out more; lower values approach an ease. Defaults to
     * 1.42. Must be positive.
     */
    public val curve: Double?,
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
    public actual val duration: Duration?
      get() = fields.duration

    public actual val speed: Double
      get() = fields.speed

    public actual override val easing: CubicBezier
      get() = fields.easing

    private constructor(
      builder: Builder
    ) : this(
      builder.fields.build(),
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
      require(curve == null || curve > 0.0) { "Flight curve must be positive: $curve" }
      require(screenSpeed == null || screenSpeed > 0.0) {
        "Flight screen speed must be positive: $screenSpeed"
      }
      require(maxDuration == null || maxDuration.isPositive()) {
        "Flight maximum duration must be positive: $maxDuration"
      }
    }

    actual override fun equals(other: Any?): Boolean =
      other is Fly &&
        fields == other.fields &&
        minZoom == other.minZoom &&
        curve == other.curve &&
        screenSpeed == other.screenSpeed &&
        maxDuration == other.maxDuration

    actual override fun hashCode(): Int {
      var result = fields.hashCode()
      result = 31 * result + (minZoom?.hashCode() ?: 0)
      result = 31 * result + (curve?.hashCode() ?: 0)
      result = 31 * result + (screenSpeed?.hashCode() ?: 0)
      return 31 * result + (maxDuration?.hashCode() ?: 0)
    }

    actual override fun toString(): String =
      formatToString(
        "Fly",
        "duration" to duration,
        "speed" to speed,
        "minZoom" to minZoom,
        "curve" to curve,
        "screenSpeed" to screenSpeed,
        "maxDuration" to maxDuration,
        "easing" to easing,
      )

    @MapOptionsDsl
    public actual class Builder internal actual constructor(from: Fly?) {
      internal val fields = FlyFields.Builder(from?.fields)
      public actual var duration: Duration?
        get() = fields.duration
        set(value) {
          fields.duration = value
        }

      public actual var speed: Double
        get() = fields.speed
        set(value) {
          fields.speed = value
        }

      public actual var easing: CubicBezier
        get() = fields.easing
        set(value) {
          fields.easing = value
        }

      /** See [Fly.minZoom]. */
      public var minZoom: Double? = from?.minZoom
      /** See [Fly.curve]. */
      public var curve: Double? = from?.curve
      /** See [Fly.screenSpeed]. */
      public var screenSpeed: Double? = from?.screenSpeed
      /** See [Fly.maxDuration]. */
      public var maxDuration: Duration? = from?.maxDuration
    }

    public actual companion object {
      public actual const val DefaultSpeed: Double = FlyFields.DefaultSpeed
      public actual val Standard: Fly = Fly(Builder(from = null))
    }
  }
}
