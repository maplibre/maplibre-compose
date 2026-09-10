package org.maplibre.compose.interaction

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import org.maplibre.compose.interaction.internal.GesturePointerSample
import org.maplibre.spatialk.geojson.Position

/**
 * A map click in map-local dp, before camera padding. [position] uses the viewport snapshot
 * available before the response and can be null when projection is unavailable. [buttons] contains
 * only physical buttons, including for touch and classified trackpad input.
 */
@Immutable
public class ClickEvent internal constructor(sample: GesturePointerSample) {
  public val uptimeMillis: Long = sample.uptimeMillis
  public val screenOffset: DpOffset = sample.screenOffset
  /** Geographic position in the pointed-to world copy; longitude may fall outside ±180°. */
  public val position: Position? = sample.position
  /** Android accessibility gestures may report [PointerType.Unknown]. */
  public val pointerTypes: Set<PointerType> = sample.pointerTypes.toSet()
  public val buttons: Set<PointerButton> = sample.buttons.toSet()
  public val modifierKeys: Set<KeyModifier> = sample.modifierKeys.toSet()
}
