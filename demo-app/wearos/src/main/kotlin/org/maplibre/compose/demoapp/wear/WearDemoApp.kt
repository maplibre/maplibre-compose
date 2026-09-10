package org.maplibre.compose.demoapp.wear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TextButtonDefaults
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.Protomaps
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.AttributionLinks
import org.maplibre.compose.overlay.attributions
import org.maplibre.spatialk.geojson.Position

private val InitialCamera = CameraPosition(target = Position(-74.006, 40.7128), zoom = 12.0)

/** A touch- and crown-controlled map with a small Wear Compose overlay. */
@Composable
fun WearDemoApp() {
  val state =
    rememberMapState(baseStyle = Protomaps.Dark.base, initialCameraPosition = InitialCamera)
  val scope = rememberCoroutineScope()
  var creditsOpen by rememberSaveable { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val ready = state.style.loadState == StyleLoadState.Ready
  // The crown zooms the focused map once its style is ready.
  LaunchedEffect(ready, creditsOpen) { if (ready && !creditsOpen) focusRequester.requestFocus() }
  MaterialTheme {
    AppScaffold(timeText = {}) {
      ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
          MaplibreMap(
            state = state,
            modifier = Modifier.focusRequester(focusRequester),
            cameraPadding = PaddingValues(top = 36.dp, bottom = 56.dp),
            overlay = {},
          )
          TextButton(
            onClick = { creditsOpen = true },
            colors = TextButtonDefaults.filledTonalTextButtonColors(),
            modifier =
              Modifier.align(Alignment.TopCenter).padding(top = 12.dp).semantics {
                contentDescription = "Map credits"
              },
          ) {
            Text("i")
          }
          EdgeButton(
            onClick = { scope.launch { state.animateCameraPosition(InitialCamera) } },
            buttonSize = EdgeButtonSize.Small,
            modifier = Modifier.align(Alignment.BottomCenter),
          ) {
            Text("Recenter")
          }
        }
      }
      AlertDialog(
        visible = creditsOpen,
        onDismissRequest = { creditsOpen = false },
        title = { Text("Map credits") },
        text = {
          AttributionLinks(
            attributions = state.style.attributions(),
            textStyle =
              MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onBackground
              ),
            breakWithinAttribution = true,
          )
        },
      )
    }
  }
}
