@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import android.content.Context
import android.content.res.Configuration
import android.view.Surface
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
