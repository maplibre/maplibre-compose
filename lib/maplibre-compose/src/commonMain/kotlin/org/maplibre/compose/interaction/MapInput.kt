package org.maplibre.compose.interaction

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.compose.interaction.internal.rememberMapInput
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

/**
 * Lets this composable control the map that a presentation shows for [state], with the same
 * pointer, scroll, key, and rotary input as [MaplibreMap].
 *
 * The composable must cover the presented map exactly, at the same density as the presentation. The
 * view under it must not consume pointer events. The composable draws the box-zoom selection while
 * the user drags it, and draws the [androidx.compose.foundation.LocalIndication] while it has
 * focus.
 *
 * Camera permissions, click callbacks, and animation duration come from the presentation's
 * [MapInteractions]. While no presentation shows [state], this modifier does nothing.
 *
 * @param state The map to control. The caller controls its lifetime.
 * @param from The bindings to start from.
 * @param block Changes to [from]. Settings that it does not change keep their values in [from].
 * @see MapState.panBy
 */
@ExperimentalMaplibreComposeApi
@Composable
public fun Modifier.mapInput(
  state: MapState,
  from: InteractionBindings = InteractionBindings.Standard,
  block: InteractionBindingsBuilder.() -> Unit = {},
): Modifier {
  val input =
    rememberMapInput(state, state.inputAttachment, InteractionBindings(from, block).resolved)
  return then(input.decoration).then(input.input)
}
