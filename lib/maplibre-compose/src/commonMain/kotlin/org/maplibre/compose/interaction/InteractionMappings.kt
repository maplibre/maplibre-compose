package org.maplibre.compose.interaction

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import org.maplibre.compose.interaction.internal.DragMapping
import org.maplibre.compose.interaction.internal.KeyMapping
import org.maplibre.compose.interaction.internal.PointerPattern
import org.maplibre.compose.interaction.internal.ScrollMapping
import org.maplibre.compose.interaction.internal.TapMapping

/** Ordered mappings; the first matching permitted response wins. */
@MapInteractionDsl
public class DragMappingsBuilder internal constructor() {
  private val rows = mutableListOf<DragMapping>()
  private var hasOtherwise = false

  public fun on(
    pointerTypes: Set<PointerType>? = null,
    button: PointerButton? = null,
    modifiers: ModifierMatch? = null,
    response: DragResponse,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows +=
      DragMapping(
        PointerPattern(pointerTypes?.toSet(), button, modifiers),
        response,
      )
  }

  public fun otherwise(response: DragResponse) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += DragMapping(PointerPattern(), response)
  }

  internal fun build(): List<DragMapping> = rows.toList()
}

/** Ordered mappings; the first matching permitted response wins. */
@MapInteractionDsl
public class ScrollMappingsBuilder internal constructor() {
  private val rows = mutableListOf<ScrollMapping>()
  private var hasOtherwise = false

  public fun on(
    pointerTypes: Set<PointerType>? = null,
    modifiers: ModifierMatch? = null,
    response: ScrollResponse,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows +=
      ScrollMapping(
        PointerPattern(pointerTypes?.toSet(), modifiers = modifiers),
        response,
      )
  }

  public fun otherwise(response: ScrollResponse) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += ScrollMapping(PointerPattern(), response)
  }

  internal fun build(): List<ScrollMapping> = rows.toList()
}

/**
 * Ordered mappings; the first matching permitted response wins. The enclosing tap binding selects
 * the mouse button: primary for tap and double tap, secondary for secondary click.
 */
@MapInteractionDsl
public class TapMappingsBuilder internal constructor() {
  private val rows = mutableListOf<TapMapping>()
  private var hasOtherwise = false

  public fun on(
    pointerTypes: Set<PointerType>? = null,
    modifiers: ModifierMatch? = null,
    response: TapResponse,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows +=
      TapMapping(
        PointerPattern(pointerTypes = pointerTypes?.toSet(), modifiers = modifiers),
        response,
      )
  }

  public fun otherwise(response: TapResponse) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += TapMapping(PointerPattern(), response)
  }

  internal fun build(): List<TapMapping> = rows.toList()
}

/** Ordered mappings; the first matching permitted response wins. */
@MapInteractionDsl
public class KeyMappingsBuilder internal constructor() {
  private val rows = mutableListOf<KeyMapping>()
  private var hasOtherwise = false

  /** Modifiers match exactly by default, so unconfigured system shortcuts remain unclaimed. */
  public fun on(
    key: Key,
    modifiers: ModifierMatch? = ModifierMatch.Exactly(),
    response: KeyResponse,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows += KeyMapping(key, modifiers, response)
  }

  public fun otherwise(response: KeyResponse) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += KeyMapping(null, null, response)
  }

  internal fun build(): List<KeyMapping> = rows.toList()
}
