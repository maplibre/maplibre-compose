package org.maplibre.compose.map

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MapOverlayHost
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.overlay.include

/**
 * Displays [state] on a map surface. The caller controls the lifetime of a supplied [state].
 *
 * [overlay] draws Compose UI over the map. A supplied block replaces [MapOverlay.Default].
 *
 * [viewportInsets] adds to [org.maplibre.compose.camera.CameraPosition.padding] for camera moves
 * and fitting. Built-in controls also use these insets.
 */
@Composable
public fun MaplibreMap(
  modifier: Modifier = Modifier,
  state: MapState = rememberMapState(),
  viewportInsets: PaddingValues = PaddingValues(0.dp),
  cameraConstraints: CameraConstraints = CameraConstraints(),
  renderOptions: RenderOptions = RenderOptions.Standard,
  interactions: MapInteractions = MapInteractions.Standard,
  uiOptions: MapUiOptions = MapUiOptions.Standard,
  overlay: @Composable @UiComposable MapOverlayScope.() -> Unit = {
    include(MapOverlay.Default)
  },
) {
  if (LocalInspectionMode.current) {
    Box(modifier = modifier.fillMaxSize().background(Color.Gray))
    return
  }

  val presentationHostIdentity = mapPresentationHostIdentity()
  val presentationOwner = remember(state) { MapPresentationOwnerToken() }
  val mapViewOptions =
    MapViewOptions(
      viewportInsets = viewportInsets,
      cameraConstraints = cameraConstraints,
      renderOptions = renderOptions,
      interactions = interactions,
      uiOptions = uiOptions,
    )
  key(state, presentationHostIdentity) {
    val presentation = rememberComposeMapPresentation(state, presentationOwner, mapViewOptions)
    MapInputHost(modifier.fillMaxSize(), state, mapViewOptions, presentation) {
      MapOverlayHost(
        overlay = overlay,
        mapState = state,
        viewportInsets = viewportInsets,
        modifier = Modifier.matchParentSize().focusGroup(),
      )
    }
  }
}
