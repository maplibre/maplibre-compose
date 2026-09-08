@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.maplibre.compose.map.MapRuntime
import org.maplibre.compose.map.MapSnapshotRequest
import org.maplibre.compose.map.MapState
import org.maplibre.compose.util.MaplibreComposable

// #region capture
suspend fun captureCurrentMap(
  runtime: MapRuntime,
  mapState: MapState,
  content: @Composable @MaplibreComposable () -> Unit,
  density: Density,
  layoutDirection: LayoutDirection,
): ImageBitmap {
  val snapshotter =
    runtime.createSnapshotter(
      baseStyle = mapState.style.baseStyle,
      content = content,
    )
  return try {
    snapshotter.capture(
      MapSnapshotRequest(
        width = 640,
        height = 360,
        cameraPosition = mapState.cameraPosition,
        density = density.density,
        fontScale = density.fontScale,
        layoutDirection = layoutDirection,
      )
    )
  } finally {
    withContext(NonCancellable) {
      snapshotter.close()
      snapshotter.awaitClosed()
    }
  }
}
// #endregion capture
