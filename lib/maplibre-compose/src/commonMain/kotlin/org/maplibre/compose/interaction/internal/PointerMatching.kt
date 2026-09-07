package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.pointer.PointerType
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.ModifierMatch
import org.maplibre.compose.interaction.PointerButton

/** Internal metadata pattern shared by admission, demand, and routing. */
internal data class PointerPattern(
  val pointerTypes: Set<PointerType>? = null,
  val button: PointerButton? = null,
  val modifiers: ModifierMatch = ModifierMatch.Any,
) {
  fun matches(
    types: Set<PointerType>,
    buttons: Set<PointerButton>,
    modifierKeys: Set<KeyModifier>,
    contact: Boolean,
  ): Boolean =
    (pointerTypes?.let { allowed -> types.all { it in allowed } } != false) &&
      (button == null ||
        button in buttons ||
        (button == PointerButton.Primary &&
          contact &&
          types.isNotEmpty() &&
          types.all {
            it == PointerType.Touch || it == PointerType.Stylus || it == PointerType.Eraser
          })) &&
      modifiers.matches(modifierKeys)
}
