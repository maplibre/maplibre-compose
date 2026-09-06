package org.maplibre.compose.interaction

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.interaction.internal.CameraConfiguration
import org.maplibre.compose.interaction.internal.InteractionBindings
import org.maplibre.compose.interaction.internal.InteractionCallbacks
import org.maplibre.compose.interaction.internal.requireNonnegativeFinite

/** Keeps configuration blocks scoped to their current input or camera component. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class MapInteractionDsl

/**
 * Immutable camera policy, input mappings, and application callbacks for one map. Callback bodies
 * and subscription presence can update without restarting input. Changes to permissions, matching
 * patterns, or tuning cancel input using the previous configuration.
 */
@Immutable
public class MapInteractions
private constructor(
  internal val camera: CameraConfiguration,
  internal val bindings: InteractionBindings,
  internal val callbacks: InteractionCallbacks,
  public val animationDuration: Duration = 300.milliseconds,
) {
  /** Edits [from]; omitted settings inherit. Mapping blocks replace their family's table. */
  public constructor(
    from: MapInteractions = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(
    builder.cameraBuilder.build(),
    builder.bindingsBuilder.build(builder.cameraBuilder.build()),
    builder.callbacksBuilder.build(),
    builder.animationDuration,
  )

  internal val structuralKey: Any =
    listOf(camera.structuralKey, bindings.structuralKey, animationDuration)

  override fun equals(other: Any?): Boolean =
    other is MapInteractions &&
      camera == other.camera &&
      bindings == other.bindings &&
      callbacks == other.callbacks &&
      animationDuration == other.animationDuration

  override fun hashCode(): Int = listOf(camera, bindings, callbacks, animationDuration).hashCode()

  init {
    requireNonnegativeFinite(animationDuration, "animationDuration")
  }

  @MapInteractionDsl
  public class Builder internal constructor(from: MapInteractions) {
    public var animationDuration: Duration = from.animationDuration
    internal val cameraBuilder = CameraBuilder(from.camera)
    internal val bindingsBuilder = InteractionBindingsBuilder(from.bindings)
    internal val callbacksBuilder = InteractionCallbacksBuilder(from.callbacks)

    public fun camera(block: CameraBuilder.() -> Unit) {
      cameraBuilder.apply(block)
    }

    public fun bindings(block: InteractionBindingsBuilder.() -> Unit) {
      bindingsBuilder.apply(block)
    }

    public fun callbacks(block: InteractionCallbacksBuilder.() -> Unit) {
      callbacksBuilder.apply(block)
    }
  }

  public companion object {
    /** Standard camera controls with no application subscriptions. */
    public val Standard: MapInteractions =
      MapInteractions(
        CameraConfiguration(),
        InteractionBindings.standard(),
        InteractionCallbacks(),
      )
    /** Disables built-in input, including feature clicks and hover. */
    public val None: MapInteractions =
      MapInteractions(
        CameraConfiguration(),
        InteractionBindings.none(),
        InteractionCallbacks(),
      )
  }
}
