@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.WebMapPresentation
import web.html.HTMLElement

// #region web-container
/** The caller owns the MapState and gives the container a CSS width and height. */
class WebMapHost(state: MapState, container: HTMLElement) : AutoCloseable {
  val presentation = WebMapPresentation(state)
  private val binding = presentation.attachContainer(container)

  override fun close() {
    binding.close()
    presentation.close()
  }
}
// #endregion web-container
