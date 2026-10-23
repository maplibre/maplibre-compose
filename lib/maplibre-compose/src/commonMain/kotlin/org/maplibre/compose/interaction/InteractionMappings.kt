package org.maplibre.compose.interaction

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import org.maplibre.compose.interaction.internal.DragMapping
import org.maplibre.compose.interaction.internal.KeyMapping
import org.maplibre.compose.interaction.internal.PointerPattern
import org.maplibre.compose.interaction.internal.ScrollMapping
import org.maplibre.compose.interaction.internal.TapMapping

/**
 * Ordered mappings. The first matching row selects its action, skipping rows whose camera action
 * the camera settings in [MapInteractions] disable.
 */
@MapInteractionDsl
public class DragMappingsBuilder internal constructor() {
  private val rows = mutableListOf<DragMapping>()
  private var hasOtherwise = false

  /**
   * Adds a row for [action] that matches when every pointer's type is in [pointerTypes], [button]
   * is pressed, and the pressed modifier keys satisfy [modifiers]. A `null` criterion matches any
   * input. [PointerButton.Primary] also matches touch and stylus contact.
   *
   * @throws IllegalArgumentException if [otherwise] was already called.
   */
  public fun on(
    pointerTypes: Set<PointerType>? = null,
    button: PointerButton? = null,
    modifiers: ModifierMatch? = null,
    action: DragAction,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows +=
      DragMapping(
        PointerPattern(pointerTypes?.toSet(), button, modifiers),
        action,
      )
  }

  /**
   * Adds a final row for [action] that matches any drag.
   *
   * @throws IllegalArgumentException if [otherwise] was already called.
   */
  public fun otherwise(action: DragAction) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += DragMapping(PointerPattern(), action)
  }

  internal fun build(): List<DragMapping> = rows.toList()
}

/**
 * Ordered mappings. The first matching row selects its action, skipping rows whose camera action
 * the camera settings in [MapInteractions] disable.
 */
@MapInteractionDsl
public class ScrollMappingsBuilder internal constructor() {
  private val rows = mutableListOf<ScrollMapping>()
  private var hasOtherwise = false

  /**
   * Adds a row for [action] that matches when every pointer's type is in [pointerTypes] and the
   * pressed modifier keys satisfy [modifiers]. A `null` criterion matches any input.
   *
   * @throws IllegalArgumentException if [otherwise] was already called.
   */
  public fun on(
    pointerTypes: Set<PointerType>? = null,
    modifiers: ModifierMatch? = null,
    action: ScrollAction,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows +=
      ScrollMapping(
        PointerPattern(pointerTypes?.toSet(), modifiers = modifiers),
        action,
      )
  }

  /**
   * Adds a final row for [action] that matches any scroll.
   *
   * @throws IllegalArgumentException if [otherwise] was already called.
   */
  public fun otherwise(action: ScrollAction) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += ScrollMapping(PointerPattern(), action)
  }

  internal fun build(): List<ScrollMapping> = rows.toList()
}

/**
 * Ordered mappings. The first matching row selects its action, skipping rows whose camera action
 * the camera settings in [MapInteractions] disable. The enclosing tap binding selects the mouse
 * button: primary for tap and double tap, secondary for secondary click.
 */
@MapInteractionDsl
public class TapMappingsBuilder internal constructor() {
  private val rows = mutableListOf<TapMapping>()
  private var hasOtherwise = false

  /**
   * Adds a row for [action] that matches when every pointer's type is in [pointerTypes] and the
   * pressed modifier keys satisfy [modifiers]. A `null` criterion matches any input.
   *
   * @throws IllegalArgumentException if [otherwise] was already called.
   */
  public fun on(
    pointerTypes: Set<PointerType>? = null,
    modifiers: ModifierMatch? = null,
    action: TapAction,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows +=
      TapMapping(
        PointerPattern(pointerTypes = pointerTypes?.toSet(), modifiers = modifiers),
        action,
      )
  }

  /**
   * Adds a final row for [action] that matches any tap.
   *
   * @throws IllegalArgumentException if [otherwise] was already called.
   */
  public fun otherwise(action: TapAction) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += TapMapping(PointerPattern(), action)
  }

  internal fun build(): List<TapMapping> = rows.toList()
}

/**
 * Ordered mappings. The first matching row selects its action, skipping rows whose camera action
 * the camera settings in [MapInteractions] disable.
 */
@MapInteractionDsl
public class KeyMappingsBuilder internal constructor() {
  private val rows = mutableListOf<KeyMapping>()
  private var hasOtherwise = false

  /**
   * Adds a row for [action] that matches when [key] is pressed and the pressed modifier keys
   * satisfy [modifiers].
   *
   * @param modifiers `null` matches any modifier keys. Modifiers match exactly by default, so
   *   unconfigured system shortcuts remain unclaimed.
   * @throws IllegalArgumentException if [otherwise] was already called.
   */
  public fun on(
    key: Key,
    modifiers: ModifierMatch? = ModifierMatch.Exactly(),
    action: KeyAction,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows += KeyMapping(key, modifiers, action)
  }

  /**
   * Adds a final row for [action] that matches any key.
   *
   * @throws IllegalArgumentException if [otherwise] was already called.
   */
  public fun otherwise(action: KeyAction) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += KeyMapping(null, null, action)
  }

  internal fun build(): List<KeyMapping> = rows.toList()
}
