package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize

@Composable internal actual fun rememberScrollConverter(): ScrollConverter = BrowserScrollConverter

// Match Compose's initial line height, with a 16 CSS-pixel fallback.
private val browserLineHeight: Float by lazy { measureBrowserLineHeight().toFloat() }

private fun measureBrowserLineHeight(): Double =
  js(
    "{ const body = document.body; if (!body) return 16; const probe = document.createElement('div'); probe.style.fontSize = 'initial'; probe.style.display = 'none'; body.appendChild(probe); try { return parseFloat(window.getComputedStyle(probe).fontSize) || 16; } finally { body.removeChild(probe); } }"
  )

private val BrowserScrollConverter: ScrollConverter = { event, density, bounds ->
  @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE", "USELESS_CAST")
  val mode = wheelDeltaMode(event.nativeEvent as? kotlin.js.JsAny)
  browserScrollDelta(
    event.totalScrollDelta,
    mode,
    density,
    bounds,
    if (mode == 1) browserLineHeight else 1f,
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
    1 -> raw * -lineHeight * density.density
    2 -> Offset(-raw.x * bounds.width, -raw.y * bounds.height)
    0 -> raw * -density.density
    // Compose-generated scroll events without DOM metadata use CSS pixels too.
    else -> raw * -density.density
  }

private fun wheelDeltaMode(event: kotlin.js.JsAny?): Int? =
  js("event == null || event.deltaMode == null ? null : event.deltaMode")
