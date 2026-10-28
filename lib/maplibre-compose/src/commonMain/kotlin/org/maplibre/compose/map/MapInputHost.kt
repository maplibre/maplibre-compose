package org.maplibre.compose.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import org.maplibre.compose.interaction.internal.MapInputAttachment
import org.maplibre.compose.interaction.internal.rememberMapInput

/** Owns input and focus outside the replaceable renderer's lifetime. */
@Composable
internal fun MapInputHost(
  modifier: Modifier,
  state: MapState,
  options: MapViewOptions,
  presentation: ComposeMapPresentation?,
  overlay: @Composable BoxScope.() -> Unit,
) {
  val source = presentation?.let {
    MapInputAttachment(
      it.target,
      it.clicks::capture,
      it.clicks::hasHandlers,
      { options.interactions },
      it.onEngagedChanged,
    )
  }
  val input = rememberMapInput(state, source, options.uiOptions.bindings)
  Box(modifier.then(input.input)) {
    if (presentation != null)
      key(presentation.target) {
        presentation.surface(Modifier.fillMaxSize().then(input.decoration))
      }
    overlay()
  }
}
