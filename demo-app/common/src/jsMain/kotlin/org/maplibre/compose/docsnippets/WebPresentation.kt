@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.WebMapPresentation
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi
import web.html.HTMLElement

// #region web-container
/** Give the container a CSS width and height. */
@OptIn(ExperimentalMaplibreComposeApi::class)
class WebMapHost(container: HTMLElement) : AutoCloseable {
  val state =
    DefaultMapRuntime.instance.createMapState(
      baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")
    ) {
      // Sources and layers, as in rememberMapState
    }
  val presentation = WebMapPresentation(state)
  private val binding = presentation.attachContainer(container)

  override fun close() {
    binding.close()
    presentation.close()
    state.close()
  }
}
// #endregion web-container
