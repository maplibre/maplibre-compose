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
     * Approximate lowest zoom used to shape the path. Native fits the flight toward this value,
     * even if the natural path stays above it. This is not a hard limit. Null uses the engine's
     * path.
     */
    public val minZoom: Double?,
  ) : CameraAnimation {
    public actual val duration: Duration?
      get() = fields.duration

    public actual val speed: Double
      get() = fields.speed

    public actual override val easing: CubicBezier
      get() = fields.easing

    private constructor(builder: Builder) : this(builder.fields.build(), builder.minZoom)

    public actual constructor(
      from: Fly,
      block: Builder.() -> Unit,
    ) : this(Builder(from).apply(block))

    actual override fun equals(other: Any?): Boolean =
      other is Fly && fields == other.fields && minZoom == other.minZoom

    actual override fun hashCode(): Int = 31 * fields.hashCode() + (minZoom?.hashCode() ?: 0)

    actual override fun toString(): String =
      formatToString(
        "Fly",
        "duration" to duration,
        "speed" to speed,
        "minZoom" to minZoom,
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
    }

    public actual companion object {
      public actual const val DefaultSpeed: Double = FlyFields.DefaultSpeed
      public actual val Standard: Fly = Fly(Builder(from = null))
    }
  }
}
