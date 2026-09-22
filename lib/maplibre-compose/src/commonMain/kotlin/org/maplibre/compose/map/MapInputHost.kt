package org.maplibre.compose.map

import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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

/** Owns input and focus outside the replaceable renderer's lifetime. */
@Composable
internal fun MapInputHost(
  modifier: Modifier,
  state: MapState,
  options: MapViewOptions,
  presentation: ComposeMapPresentation?,
  overlay: @Composable BoxScope.() -> Unit,
) {
  val currentPresentation by rememberUpdatedState(presentation)
  val focusRequester = remember { FocusRequester() }
  val focus = remember { InputFocus { currentPresentation?.onEngagedChanged?.invoke(it) } }
  val target = presentation?.target
  val boxZoom = remember(target) { BoxZoomPreview() }
  val attached = target != null && state.currentMapAttachment?.adapter === target
  LaunchedEffect(target, attached) { if (attached) focus.replay() }
  val environment = inputEnvironment()
  val input =
    if (presentation == null) Modifier
    else
      Modifier.mapInput(
        presentation.target,
        presentation.clicks::capture,
        presentation.clicks::hasHandlers,
        InputConfiguration(options.interactions, options.uiOptions.bindings),
        LocalDensity.current,
        focusRequester,
        focus,
        environment,
        rotaryNotchPixels(),
        boxZoom,
      )
  Box(modifier.then(input)) {
    if (presentation != null)
      key(target) {
        presentation.surface(
          Modifier.fillMaxSize()
            .drawBoxZoom(boxZoom)
            .indication(focus.indicationInteractions, environment.indication)
        )
      }
    overlay()
  }
}
