@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import android.content.Context
import android.content.res.Configuration
import android.view.Surface
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import org.maplibre.compose.map.AndroidMapPresentation
import org.maplibre.compose.map.MapState

// #region surface-host
/** Your code owns the MapState and each Surface. Call these methods on the main thread. */
class SurfaceMapHost(context: Context, state: MapState, lifecycle: Lifecycle) : AutoCloseable {
  val presentation = AndroidMapPresentation(context, state, lifecycle)
  private var binding: AndroidMapPresentation.SurfaceBinding? = null

  fun onSurfaceAvailable(surface: Surface, width: Int, height: Int, density: Float) {
    binding = presentation.attachSurface(surface, width, height, density)
  }

  fun onSurfaceChanged(width: Int, height: Int, density: Float) {
    binding?.update(width, height, density)
  }

  fun onSurfaceDestroyed() {
    binding?.close()
    binding = null
  }

  fun onConfigurationChanged(configuration: Configuration) {
    presentation.updateConfiguration(configuration)
  }

  override fun close() {
    presentation.close()
  }
}

// #endregion surface-host

// #region surface-input
/** Converts physical pixels to the map's logical pixels before passing gestures. */
class SurfaceInput(private val state: MapState, private val density: Float) {
  fun pan(deltaX: Float, deltaY: Float) {
    state.panBy(logical(deltaX, deltaY))
  }

  fun fling(velocityX: Float, velocityY: Float) {
    state.fling(logical(velocityX, velocityY))
  }

  fun scale(factor: Float, focusX: Float?, focusY: Float?) {
    val anchor = if (focusX != null && focusY != null) logical(focusX, focusY) else null
    state.scaleBy(factor.toDouble(), anchor)
  }

  fun click(x: Float, y: Float) {
    state.click(logical(x, y))
  }

  private fun logical(x: Float, y: Float) = DpOffset((x / density).dp, (y / density).dp)
}
// #endregion surface-input
