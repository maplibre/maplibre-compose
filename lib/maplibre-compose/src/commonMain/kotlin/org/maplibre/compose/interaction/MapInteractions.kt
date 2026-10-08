package org.maplibre.compose.interaction

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.interaction.internal.CameraConfiguration
import org.maplibre.compose.interaction.internal.InteractionCallbacks
import org.maplibre.compose.interaction.internal.requireNonnegativeFinite
import org.maplibre.compose.util.formatToString

/** Keeps configuration blocks scoped to their current input or camera component. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class MapInteractionDsl

/**
 * Which camera movements are allowed, how they settle, and how the app responds to clicks.
 * Callbacks can update without restarting input. Changes to permissions or tuning cancel input in
 * progress.
 *
 * What gestures, scrolling, and keys do is set in [org.maplibre.compose.map.MapUiOptions].
 */
@Immutable
public data class MapInteractions
private constructor(
  public val animationDuration: Duration = 300.milliseconds,
  internal val camera: CameraConfiguration,
  internal val callbacks: InteractionCallbacks,
) {
  /** Edits [from]; omitted settings inherit. */
  public constructor(
    from: MapInteractions = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(
    builder.animationDuration,
    builder.cameraBuilder.build(),
    builder.callbacksBuilder.build(),
  )

  // Callbacks are functions, so only their presence is shown.
  override fun toString(): String =
    formatToString(
      "MapInteractions",
      "camera" to camera.settings,
      "cameraStartCallbacks" to camera.onStart.keys,
      "callbacks" to
        listOfNotNull(
          callbacks.click?.let { "click" },
          callbacks.unhandledClick?.let { "unhandledClick" },
          callbacks.doubleClick?.let { "doubleClick" },
          callbacks.longClick?.let { "longClick" },
        ),
      "animationDuration" to animationDuration,
    )

  init {
    requireNonnegativeFinite(animationDuration, "animationDuration")
  }

  @MapInteractionDsl
  public class Builder internal constructor(from: MapInteractions) {
    public var animationDuration: Duration = from.animationDuration
    internal val cameraBuilder = CameraBuilder(from.camera)
    internal val callbacksBuilder = InteractionCallbacksBuilder(from.callbacks)

    public fun camera(block: CameraBuilder.() -> Unit) {
      cameraBuilder.apply(block)
    }

    public fun callbacks(block: InteractionCallbacksBuilder.() -> Unit) {
      callbacksBuilder.apply(block)
    }
  }

  public companion object {
    /** Every camera movement allowed, with no callbacks. */
    public val Standard: MapInteractions =
      MapInteractions(camera = CameraConfiguration(), callbacks = InteractionCallbacks())

    /** No camera movement allowed and no callbacks. */
    public val None: MapInteractions =
      MapInteractions(Standard) {
        camera {
          pan { enabled = false }
          zoom { enabled = false }
          rotate { enabled = false }
          pitch { enabled = false }
        }
      }
  }
}
