package org.maplibre.compose.demoapp.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoAppTheme
import org.maplibre.compose.demoapp.DemoMap
import org.maplibre.compose.demoapp.MapStyleMode
import org.maplibre.compose.demoapp.MapViewportInsets
import org.maplibre.compose.demoapp.allDemos
import org.maplibre.compose.demoapp.rememberDemoAppState
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.overlay.AttributionLinks
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.attributions

/**
 * The watch shell: a full-screen map, and one tap away a list of demos with the selected demo's
 * controls above it. The map itself handles the crown.
 */
@Composable
fun WearDemoApp(state: DemoAppState = rememberDemoAppState()) {
  LaunchedEffect(state.settings) { state.settings.mapStyleMode = MapStyleMode.Dark }
  // The demo panels compose against the phone Material theme.
  DemoAppTheme(state) { MaterialTheme { WearShell(state) } }
}

/** How far the edge button reaches into the map, kept clear of the camera's target. */
private val EdgeButtonInset = 56.dp

/**
 * The demo list slides over the map rather than replacing it in a navigation graph, so the map
 * keeps its surface and its loaded style.
 */
@Composable
private fun WearShell(state: DemoAppState) {
  var listOpen by rememberSaveable { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val dark = state.settings.mapStyleMode.isDark
  var flightJob by remember { mutableStateOf<Job?>(null) }
  AppScaffold(timeText = {}) {
    MapScreen(state, active = !listOpen, onOpenDemos = { listOpen = true })
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
              flightJob?.cancel()
              flightJob = scope.launch { state.openDemo(demo, dark) { listOpen = false } }
            },
          )
        }
      }
    }
  }
}

@Composable
private fun MapScreen(state: DemoAppState, active: Boolean, onOpenDemos: () -> Unit) {
  val focusRequester = remember { FocusRequester() }
  // The crown zooms the focused map, and the map becomes focusable once its style is ready.
  val styleReady = state.mapState.style.loadState == StyleLoadState.Ready
  LaunchedEffect(styleReady, active) { if (styleReady && active) focusRequester.requestFocus() }
  ScreenScaffold {
    Box(Modifier.fillMaxSize()) {
      DemoMap(
        state,
        viewportInsets = MapViewportInsets(bottom = EdgeButtonInset),
        overlay = MapOverlay.None,
        modifier = Modifier.focusRequester(focusRequester),
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
      state.selectedDemo?.let { demo ->
        item { ListHeader { Text(demo.name) } }
        item { Column { with(demo) { Panel(state) } } }
      }
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
