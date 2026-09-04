package org.maplibre.compose.demoapp.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import kotlinx.coroutines.launch
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoAppTheme
import org.maplibre.compose.demoapp.DemoMap
import org.maplibre.compose.demoapp.MapStyleMode
import org.maplibre.compose.demoapp.MapViewportInsets
import org.maplibre.compose.demoapp.agent.StartAgentDriver
import org.maplibre.compose.demoapp.allDemos
import org.maplibre.compose.demoapp.rememberDemoAppState
import org.maplibre.compose.overlay.AttributionLinks
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.attributions

/** The watch shell: a full-screen map, a demo list one tap away, and the crown to zoom. */
@Composable
fun WearDemoApp(state: DemoAppState = rememberDemoAppState()) {
  StartAgentDriver(state)
  // Watches run dark.
  LaunchedEffect(state.settings) { state.settings.mapStyleMode = MapStyleMode.Dark }
  // The demo panels compose against the phone Material theme; the shell uses Wear Material.
  DemoAppTheme(state) { MaterialTheme { WearShell(state) } }
}

/** How far the edge button reaches into the map, kept clear of the camera's target. */
private val EdgeButtonInset = 56.dp

/** Zoom levels per crown detent. A detent scrolls by the platform's scroll factor. */
private const val ZoomLevelsPerDetent = 0.5f

/**
 * The demo list slides over the map rather than replacing it in a navigation graph. The map keeps
 * its surface, so returning to it costs no style reload, and the surface never goes away with a
 * frame in flight.
 */
@Composable
private fun WearShell(state: DemoAppState) {
  var listOpen by rememberSaveable { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  // The map is immersive; the list screen brings the time back.
  AppScaffold(timeText = {}) {
    Box(Modifier.fillMaxSize().hierarchicalFocusGroup(active = !listOpen)) {
      MapScreen(state, onOpenDemos = { listOpen = true })
    }
    if (listOpen) {
      BackHandler { listOpen = false }
      SwipeToDismissBox(
        onDismissed = { listOpen = false },
        modifier = Modifier.hierarchicalFocusGroup(active = true),
        backgroundScrimColor = Color.Transparent,
      ) { isBackground ->
        if (!isBackground) {
          DemosScreen(
            state,
            onOpenDemo = { demo ->
              scope.launch { state.openDemo(demo) { listOpen = false } }
            },
          )
        }
      }
    }
  }
}

@Composable
private fun MapScreen(state: DemoAppState, onOpenDemos: () -> Unit) {
  val density = LocalDensity.current
  ScreenScaffold {
    Box(
      Modifier.fillMaxSize()
        // Rotary events reach the focused node and bubble up. This node takes focus while the map
        // is the active screen, and the map keeps its own focus after a touch.
        .onRotaryScrollEvent { event ->
          val detents = with(density) { -event.verticalScrollPixels.toDp().value / 64f }
          val camera = state.mapState.cameraPosition
          state.mapState.setCameraPosition(
            camera.copy(zoom = camera.zoom + detents * ZoomLevelsPerDetent)
          )
          true
        }
        .requestFocusOnHierarchyActive()
        .focusable()
    ) {
      DemoMap(
        state,
        viewportInsets = MapViewportInsets(bottom = EdgeButtonInset),
        // The attribution sits in the demo list, where it fits; the crown replaces the zoom
        // buttons, and the rest would cover most of a watch map.
        overlay = MapOverlay.None,
      )
      EdgeButton(
        onClick = onOpenDemos,
        buttonSize = EdgeButtonSize.Small,
        modifier = Modifier.align(Alignment.BottomCenter),
      ) {
        Text("Demos")
      }
    }
  }
}

@Composable
private fun DemosScreen(state: DemoAppState, onOpenDemo: (Demo) -> Unit) {
  val listState = rememberTransformingLazyColumnState()
  ScreenScaffold(
    scrollState = listState,
    modifier = Modifier.background(MaterialTheme.colorScheme.background),
    timeText = { TimeText() },
  ) { contentPadding ->
    TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
      item { ListHeader { Text("Demos") } }
      items(allDemos) { demo ->
        Button(onClick = { onOpenDemo(demo) }, label = { Text(demo.name) })
      }
      item {
        AttributionLinks(
          attributions = state.mapState.style.attributions(),
          textStyle =
            MaterialTheme.typography.bodySmall.copy(
              color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
          breakWithinAttribution = true,
        )
      }
    }
  }
}
