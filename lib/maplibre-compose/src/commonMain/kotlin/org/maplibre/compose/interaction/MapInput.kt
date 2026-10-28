package org.maplibre.compose.interaction

import androidx.compose.foundation.indication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import org.maplibre.compose.interaction.internal.BoxZoomPreview
import org.maplibre.compose.interaction.internal.InputConfiguration
import org.maplibre.compose.interaction.internal.InputFocus
import org.maplibre.compose.interaction.internal.drawBoxZoom
import org.maplibre.compose.interaction.internal.inputEnvironment
import org.maplibre.compose.interaction.internal.mapInput
import org.maplibre.compose.interaction.internal.rotaryNotchPixels
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
  val resolved = InteractionBindings(from, block).resolved
  val attachment = state.inputAttachment
  val target = attachment?.target
  val attached = target != null && state.currentMapAttachment?.adapter === target
  val currentAttachment = rememberUpdatedState(attachment)
  val focusRequester = remember { FocusRequester() }
  val focus = remember { InputFocus { engaged -> currentAttachment.value?.setEngaged(engaged) } }
  val boxZoom = remember(target) { BoxZoomPreview() }
  LaunchedEffect(target, attached) { if (attached) focus.replay() }
  if (attachment == null || target == null) return this
  val environment = inputEnvironment()
  return drawBoxZoom(boxZoom)
    .indication(focus.indicationInteractions, environment.indication)
    .mapInput(
      target,
      attachment.captureClickPath,
      attachment.hasClickHandlers,
      InputConfiguration(attachment.interactions(), resolved),
      LocalDensity.current,
      focusRequester,
      focus,
      environment,
      rotaryNotchPixels(),
      boxZoom,
    )
}
