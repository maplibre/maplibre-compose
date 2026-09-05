package org.maplibre.compose.map

import androidx.compose.ui.input.pointer.PointerEvent

internal actual fun scrollUnits(event: PointerEvent): ScrollUnits = nativeScrollUnits

private val nativeScrollUnits: ScrollUnits by lazy {
  val os = mlnFfiOperatingSystem.lowercase()
  when {
    os.startsWith("mac") -> ScrollUnits.MacRotation
    os == "ios" || os == "ipados" -> ScrollUnits.IosIndirect
    else -> ScrollUnits.Rotation
  }
}
