package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.WheelEvent

@Composable internal actual fun rememberScrollConverter(): ScrollConverter = BrowserScrollConverter

// Match Compose's browser line height: initial font size, with a 16 CSS-pixel fallback.
private val browserLineHeight: Float by lazy {
  val body = document.body
  if (body == null) 16f
  else {
    val probe = document.createElement("div") as HTMLElement
    probe.style.fontSize = "initial"
    probe.style.display = "none"
    body.appendChild(probe)
    try {
      window.getComputedStyle(probe).fontSize.removeSuffix("px").toFloatOrNull() ?: 16f
    } finally {
      body.removeChild(probe)
    }
  }
}

private val BrowserScrollConverter: ScrollConverter = { event, density, bounds ->
  val mode = (event.nativeEvent as? WheelEvent)?.deltaMode
  browserScrollDelta(
    event.totalScrollDelta,
    mode,
    density,
    bounds,
    if (mode == WheelEvent.DOM_DELTA_LINE) browserLineHeight else 1f,
  )
}

internal fun browserScrollDelta(
  raw: Offset,
  deltaMode: Int?,
  density: Density,
  bounds: IntSize,
  lineHeight: Float,
): Offset =
  when (deltaMode) {
    WheelEvent.DOM_DELTA_LINE -> raw * -lineHeight * density.density
    WheelEvent.DOM_DELTA_PAGE -> Offset(-raw.x * bounds.width, -raw.y * bounds.height)
    WheelEvent.DOM_DELTA_PIXEL -> raw * -density.density
    // Compose-generated scroll events without DOM metadata use CSS pixels too.
    else -> raw * -density.density
  }
