package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.key.Key
import org.maplibre.compose.interaction.ModifierMatch

internal enum class DragResponse {
  Pan,
  RotateTilt,
  FitBounds,
  None,
}

internal enum class ScrollResponse {
  Pan,
  Zoom,
  None,
}

internal enum class TapResponse {
  ZoomIn,
  ZoomOut,
  None,
}

internal enum class KeyResponse {
  PanLeft,
  PanRight,
  PanUp,
  PanDown,
  ZoomIn,
  ZoomOut,
  RotateLeft,
  RotateRight,
  TiltUp,
  TiltDown,
  Engage,
  Disengage,
  Back,
  None;

  val isCamera: Boolean
    get() = this !in setOf(Engage, Disengage, Back, None)
}

internal data class DragMapping(val pattern: PointerPattern, val response: DragResponse)

internal data class ScrollMapping(
  val pattern: PointerPattern,
  val response: ScrollResponse,
)

internal data class TapMapping(val pattern: PointerPattern, val response: TapResponse)

internal data class KeyMapping(
  val key: Key?,
  val modifiers: ModifierMatch,
  val response: KeyResponse,
)
