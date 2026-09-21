package org.maplibre.compose.map

import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.interaction.internal.BoxZoomPreview
import org.maplibre.compose.interaction.internal.FeatureClickDispatcher
import org.maplibre.compose.interaction.internal.InputConfiguration
import org.maplibre.compose.interaction.internal.InputFocus
import org.maplibre.compose.interaction.internal.drawBoxZoom
import org.maplibre.compose.interaction.internal.inputEnvironment
import org.maplibre.compose.interaction.internal.mapInput
import org.maplibre.compose.interaction.internal.rotaryNotchPixels

private class MapInputBinding(
  val target: CameraInputTarget,
  val clicks: FeatureClickDispatcher,
  val setEngaged: (Boolean) -> Unit,
)

private class MapInputOwner {
  var binding by mutableStateOf<MapInputBinding?>(null)
}

private val LocalMapInputOwner = staticCompositionLocalOf<MapInputOwner?> { null }

/**
 * Registers a renderer with the input owner without moving its overlay into the renderer's
 * lifetime.
 */
@Composable
internal fun BindMapInput(
  target: CameraInputTarget,
  clicks: FeatureClickDispatcher,
  setEngaged: (Boolean) -> Unit,
) {
  val owner = LocalMapInputOwner.current ?: return
  val currentSetEngaged = rememberUpdatedState(setEngaged)
  val binding =
    remember(target, clicks) {
      MapInputBinding(target, clicks) { currentSetEngaged.value(it) }
    }
  DisposableEffect(owner, binding) {
    owner.binding = binding
    onDispose { if (owner.binding === binding) owner.binding = null }
  }
}

/** Owns input and focus at the stable ancestor of the surface and overlay. */
@Composable
internal fun MapInputHost(
  modifier: Modifier,
  state: MapState,
  options: MapViewOptions,
  content: @Composable BoxScope.(Modifier) -> Unit,
) {
  val owner = remember { MapInputOwner() }
  val focusRequester = remember { FocusRequester() }
  val focus = remember { InputFocus { owner.binding?.setEngaged?.invoke(it) } }
  val binding = owner.binding
  val attached = binding != null && state.currentMapAttachment?.adapter === binding.target
  val boxZoom = remember(binding?.target) { BoxZoomPreview() }
  LaunchedEffect(binding, attached) { if (attached) focus.replay() }
  val environment = inputEnvironment()
  val input =
    Modifier.mapInput(
      binding?.target,
      { binding?.clicks?.capture(it) },
      { binding?.clicks?.hasHandlers(it) == true },
      InputConfiguration(options.interactions, options.uiOptions.bindings),
      LocalDensity.current,
      focusRequester,
      focus,
      environment,
      rotaryNotchPixels(),
      boxZoom,
    )
  CompositionLocalProvider(LocalMapInputOwner provides owner) {
    Box(modifier.then(input)) {
      content(
        Modifier.drawBoxZoom(boxZoom)
          .indication(focus.indicationInteractions, environment.indication)
      )
    }
  }
}
