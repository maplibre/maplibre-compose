package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.key.Key
import org.maplibre.compose.interaction.DragAction
import org.maplibre.compose.interaction.KeyAction
import org.maplibre.compose.interaction.ModifierMatch
import org.maplibre.compose.interaction.ScrollAction
import org.maplibre.compose.interaction.TapAction

internal data class DragMapping(val pattern: PointerPattern, val action: DragAction)

internal data class ScrollMapping(
  val pattern: PointerPattern,
  val action: ScrollAction,
)

internal data class TapMapping(val pattern: PointerPattern, val action: TapAction)

internal data class KeyMapping(
  val key: Key?,
  val modifiers: ModifierMatch?,
  val action: KeyAction,
)
