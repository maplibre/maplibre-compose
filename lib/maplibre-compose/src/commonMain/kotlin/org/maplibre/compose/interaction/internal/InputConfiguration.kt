package org.maplibre.compose.interaction.internal

import kotlin.time.Duration
import org.maplibre.compose.interaction.MapInteractions

/**
 * What the Compose input recognizers read: the map's [MapInteractions] with the host's bindings.
 * Recognizers compare [settings] to decide whether a change restarts input.
 */
internal class InputConfiguration(
  val interactions: MapInteractions,
  val bindings: InteractionBindings,
) {
  val camera: CameraConfiguration
    get() = interactions.camera

  val callbacks: InteractionCallbacks
    get() = interactions.callbacks

  val animationDuration: Duration
    get() = interactions.animationDuration

  val settings = Settings(camera.settings, bindings, animationDuration)
  val hasCameraKeys = bindings.keys.hasCameraBindings(camera.settings)

  internal data class Settings(
    val camera: CameraSettings,
    val bindings: InteractionBindings,
    val animationDuration: Duration,
  )

  override fun equals(other: Any?): Boolean =
    other is InputConfiguration && interactions == other.interactions && bindings == other.bindings

  override fun hashCode(): Int = listOf(interactions, bindings).hashCode()

  companion object {
    val Standard = InputConfiguration(MapInteractions.Standard, InteractionBindings.standard())
  }
}
