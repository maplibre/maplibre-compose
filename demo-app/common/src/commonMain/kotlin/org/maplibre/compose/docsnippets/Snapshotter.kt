@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapSnapshotException
import org.maplibre.compose.map.MapState
import org.maplibre.compose.util.MaplibreComposable

@Composable
fun ShareMapButton(
  mapState: MapState,
  mapContent: @Composable @MaplibreComposable () -> Unit,
  onCapture: (ImageBitmap) -> Unit,
  onError: (MapSnapshotException) -> Unit,
) {
  // #region capture
  val snapshotter =
    remember(mapState.style.baseStyle) {
      DefaultMapRuntime.instance.createSnapshotter(
        baseStyle = mapState.style.baseStyle,
        content = mapContent,
      )
    }
  DisposableEffect(snapshotter) { onDispose { snapshotter.close() } }

  val scope = rememberCoroutineScope()
  val screenDensity = LocalDensity.current
  val screenLayoutDirection = LocalLayoutDirection.current
  Button(
    onClick = {
      scope.launch {
        try {
          val image =
            snapshotter.capture(DpSize(640.dp, 360.dp)) {
              cameraPosition = mapState.cameraPosition
              density = screenDensity
              layoutDirection = screenLayoutDirection
            }
          onCapture(image)
        } catch (error: MapSnapshotException) {
          onError(error)
        }
      }
    }
  ) {
    Text("Share map")
  }
  // #endregion capture
}
