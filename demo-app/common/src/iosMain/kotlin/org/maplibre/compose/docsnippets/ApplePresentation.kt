@file:Suppress("unused")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.maplibre.compose.docsnippets

import org.maplibre.compose.map.AppleMapPresentation
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MaplibreMapView
import org.maplibre.compose.style.BaseStyle
import platform.UIKit.UIView

// #region apple-view
/** Create and use this host on the main thread. */
class UiKitMapHost : AutoCloseable {
  val state =
    DefaultMapRuntime.instance.createMapState(
      baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")
    ) {
      // Sources and layers, as in rememberMapState
    }
  val presentation = AppleMapPresentation(state, isActive = false)
  val view: UIView = MaplibreMapView(presentation)

  fun onSceneActivationChanged(active: Boolean) {
    presentation.isActive = active
  }

  override fun close() {
    view.removeFromSuperview()
    presentation.close()
    state.close()
  }
}

// #endregion apple-view
