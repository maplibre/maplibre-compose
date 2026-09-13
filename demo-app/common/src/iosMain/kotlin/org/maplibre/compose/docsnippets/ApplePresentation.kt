@file:Suppress("unused")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.maplibre.compose.docsnippets

import org.maplibre.compose.map.AppleMapPresentation
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMapView
import platform.UIKit.UIView

// #region apple-view
/** The caller owns the MapState. Create and use this host on the main thread. */
class UIKitMapHost(state: MapState) : AutoCloseable {
  val presentation = AppleMapPresentation(state, isActive = false)
  val view: UIView = MaplibreMapView(presentation)

  fun onSceneActivationChanged(active: Boolean) {
    presentation.isActive = active
  }

  override fun close() {
    view.removeFromSuperview()
    presentation.close()
  }
}

// #endregion apple-view
