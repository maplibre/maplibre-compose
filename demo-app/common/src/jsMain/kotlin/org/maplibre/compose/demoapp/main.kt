package org.maplibre.compose.demoapp

import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.maplibre.compose.demoapp.ferry.FerryBoard

fun main() {
  if (window.location.search == "?ferries") {
    val stylesheet = document.createElement("link")
    stylesheet.setAttribute("rel", "stylesheet")
    stylesheet.setAttribute("href", "ferry.css")
    document.head!!.appendChild(stylesheet)
    renderComposable(rootElementId = "ferry-root") { FerryBoard() }
  } else startDemo()
}
