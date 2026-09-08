package org.maplibre.compose.interaction.internal

import kotlin.time.Duration
import org.maplibre.compose.interaction.CameraBuilder
import org.maplibre.compose.interaction.InteractionBindingsBuilder
import org.maplibre.compose.interaction.InteractionCallbacksBuilder
import org.maplibre.compose.interaction.MapInteractions

/** Builds the map's interactions and the host's bindings together, as the public builders do. */
internal fun InputConfiguration(
  from: InputConfiguration = InputConfiguration.Standard,
  block: InputConfigurationBuilder.() -> Unit,
): InputConfiguration = InputConfigurationBuilder(from).apply(block).build()

/** Standard camera policy with no bindings, so a test can enable one family at a time. */
internal val InputConfiguration.Companion.NoBindings: InputConfiguration
  get() = InputConfiguration(MapInteractions.Standard, InteractionBindings.none())

internal class InputConfigurationBuilder(private val from: InputConfiguration) {
  var animationDuration: Duration = from.animationDuration
  private val cameraBlocks = mutableListOf<CameraBuilder.() -> Unit>()
  private val callbacksBlocks = mutableListOf<InteractionCallbacksBuilder.() -> Unit>()
  private val bindingsBuilder = InteractionBindingsBuilder(from.bindings)

  fun camera(block: CameraBuilder.() -> Unit) {
    cameraBlocks += block
  }

  fun callbacks(block: InteractionCallbacksBuilder.() -> Unit) {
    callbacksBlocks += block
  }

  fun bindings(block: InteractionBindingsBuilder.() -> Unit) {
    bindingsBuilder.apply(block)
  }

  fun build(): InputConfiguration =
    InputConfiguration(
      MapInteractions(from.interactions) {
        animationDuration = this@InputConfigurationBuilder.animationDuration
        cameraBlocks.forEach { camera(it) }
        callbacksBlocks.forEach { callbacks(it) }
      },
      bindingsBuilder.build(),
    )
}
