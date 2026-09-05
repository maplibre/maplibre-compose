package org.maplibre.compose.map

import androidx.compose.ui.input.pointer.PointerEvent
import org.w3c.dom.events.WheelEvent

internal actual fun scrollUnits(event: PointerEvent): ScrollUnits =
  browserScrollUnits(event.nativeEvent)

internal fun browserScrollUnits(nativeEvent: Any?): ScrollUnits =
  when ((nativeEvent as? WheelEvent)?.deltaMode) {
    1 -> ScrollUnits.BrowserLine
    2 -> ScrollUnits.BrowserPage
    else -> ScrollUnits.BrowserPixel
  }
