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
import org.maplibre.compose.interaction.internal.InteractionBindings
import org.maplibre.compose.interaction.internal.drawBoxZoom
import org.maplibre.compose.interaction.internal.inputEnvironment
import org.maplibre.compose.interaction.internal.mapInput
import org.maplibre.compose.interaction.internal.rotaryNotchPixels
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

/**
 * Attaches this library's map pointer, scroll, key, and rotary input to the composable.
 *
 * The node is the map's hit rectangle. Apply it to a parent or overlay of the surface that presents
 * [state]; the surface itself should not consume pointer events. Input positions are measured from
 * the node's top-left corner in dp at the node's [androidx.compose.ui.platform.LocalDensity], so
 * the node must have the same bounds as the surface, and the presentation must use the same
 * density. Over its content, the node draws the box-zoom selection and, while it has focus, the
 * [androidx.compose.foundation.LocalIndication].
 *
 * Camera permissions, click callbacks, and animation duration come from the [MapInteractions] of
 * the presentation that is showing [state]. This modifier adds only the binding table: which
 * pointer, scroll, key, or rotary event does what.
 *
 * [bindings] edits the standard table. Omitted settings inherit. The modifier does nothing while no
 * map is presented.
 *
 * @param state The map to drive. The caller controls its lifetime.
 * @param bindings Edits to the standard input bindings.
 * @see MaplibreMap
 * @see MapUiOptions
 * @see MapState.panBy
 */
@ExperimentalMaplibreComposeApi
@Composable
public fun Modifier.mapInput(
  state: MapState,
  bindings: InteractionBindingsBuilder.() -> Unit = {},
): Modifier {
  val resolved = InteractionBindingsBuilder(InteractionBindings.standard()).apply(bindings).build()
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
