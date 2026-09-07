package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.key.Key
import org.maplibre.compose.interaction.DragResponse
import org.maplibre.compose.interaction.KeyResponse
import org.maplibre.compose.interaction.ModifierMatch
import org.maplibre.compose.interaction.ScrollResponse
import org.maplibre.compose.interaction.TapResponse

internal data class DragMapping(val pattern: PointerPattern, val response: DragResponse)

internal data class ScrollMapping(
  val pattern: PointerPattern,
  val response: ScrollResponse,
)

internal data class TapMapping(val pattern: PointerPattern, val response: TapResponse)

internal data class KeyMapping(
  val key: Key?,
  val modifiers: ModifierMatch?,
  val response: KeyResponse,
)
