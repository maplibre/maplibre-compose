@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import android.content.Context
import android.content.res.Configuration
import android.view.Surface
import androidx.lifecycle.Lifecycle
import org.maplibre.compose.map.AndroidMapPresentation
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.style.BaseStyle

// #region surface-host
/** Call every method on the main thread. */
class SurfaceMapHost(context: Context, lifecycle: Lifecycle) : AutoCloseable {
  val state =
    DefaultMapRuntime.instance.createMapState(
      baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")
    ) {
      // Sources and layers, as in rememberMapState
    }
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
    state.close()
  }
}

// #endregion surface-host
