package org.maplibre.compose.interaction.internal

import androidx.compose.foundation.indication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.MapState

/** The live camera target and click path of one presented [MapState]. */
internal class MapInputAttachment(
  val target: CameraInputTarget,
  val captureClickPath: (TapFamily) -> ClickPath?,
  val hasClickHandlers: (TapFamily) -> Boolean,
  val interactions: () -> MapInteractions,
  val setEngaged: (Boolean) -> Unit,
)

/**
 * [input] recognizes input on the node that covers the map. [decoration] draws the box-zoom
 * selection and focus indication over the map.
 */
internal class MapInputModifiers(val input: Modifier, val decoration: Modifier)

/**
 * Holds one input node's focus and box-zoom state across changes of [source], and reports its
 * engagement to the current [source], again once that source's map is presented. A null [source]
 * yields empty modifiers.
 */
@Composable
internal fun rememberMapInput(
  state: MapState,
  source: MapInputAttachment?,
  bindings: ResolvedInteractionBindings,
): MapInputModifiers {
  val target = source?.target
  val attached = target != null && state.currentMapAttachment?.adapter === target
  val currentSource = rememberUpdatedState(source)
  val focusRequester = remember { FocusRequester() }
  val focus = remember { InputFocus { engaged -> currentSource.value?.setEngaged(engaged) } }
  val boxZoom = remember(target) { BoxZoomPreview() }
  LaunchedEffect(target, attached) { if (attached) focus.replay() }
  if (source == null || target == null) return MapInputModifiers(Modifier, Modifier)
  val environment = inputEnvironment()
  return MapInputModifiers(
    input =
      Modifier.mapInput(
        target,
        source.captureClickPath,
        source.hasClickHandlers,
        InputConfiguration(source.interactions(), bindings),
        LocalDensity.current,
        focusRequester,
        focus,
        environment,
        rotaryNotchPixels(),
        boxZoom,
      ),
    decoration =
      Modifier.drawBoxZoom(boxZoom)
        .indication(focus.indicationInteractions, environment.indication),
  )
}
