package org.maplibre.compose.map

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
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
 * Displays [state] on a map surface.
 *
 * The caller controls the lifetime of [state]. This composable attaches the map surface while the
 * call remains in composition. [overlay] draws Compose UI over the map. The default draws
 * [MapOverlay.Default]. A supplied block replaces the default.
 *
 * The map is a focus target, and the overlay is a focus group. Focus modifiers on [modifier] apply
 * to the map, and a control in the overlay keeps its own focus properties.
 *
 * [interactions] sets which camera movements are allowed and how the app responds to clicks.
 * [uiOptions] sets what gestures, scrolling, and keys do, and what shows before the first frame.
 * Tap handlers run before interactive layers; unhandled tap callbacks run after layers pass the
 * event.
 */
@Composable
public fun MaplibreMap(
  modifier: Modifier = Modifier,
  state: MapState = rememberMapState(),
  cameraPadding: PaddingValues = PaddingValues(0.dp),
  cameraConstraints: CameraConstraints = CameraConstraints(),
  renderOptions: RenderOptions = RenderOptions.Standard,
  interactions: MapInteractions = MapInteractions.Standard,
  uiOptions: MapUiOptions = MapUiOptions.Standard,
  contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
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
      cameraPadding = cameraPadding,
      cameraConstraints = cameraConstraints,
      renderOptions = renderOptions,
      interactions = interactions,
      uiOptions = uiOptions,
    )
  key(state, presentationHostIdentity) {
    Box(modifier.fillMaxSize()) {
      ComposableMapView(
        modifier = Modifier.fillMaxSize(),
        state = state,
        presentationOwner = presentationOwner,
        options = mapViewOptions,
      )
      MapOverlayHost(
        overlay = overlay,
        mapState = state,
        contentWindowInsets = contentWindowInsets,
        modifier = Modifier.matchParentSize().focusGroup(),
      )
    }
  }
}
