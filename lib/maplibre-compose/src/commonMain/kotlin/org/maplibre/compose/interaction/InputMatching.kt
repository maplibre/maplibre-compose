package org.maplibre.compose.interaction

import androidx.compose.runtime.Immutable
import org.maplibre.compose.util.formatToString

/**
 * A physical mouse button. Touch and stylus match [Primary] without reporting a mouse button.
 *
 * Closed.
 */
public enum class PointerButton {
  Primary,
  Secondary,
  Tertiary,
  Back,
  Forward,
}

/**
 * Keyboard modifiers reported with an input sample.
 *
 * Closed.
 */
public enum class KeyModifier {
  Shift,
  Ctrl,
  Alt,
  Meta,
}

/**
 * Matches the complete modifier set or a subset. A null filter matches any modifiers.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed class ModifierMatch private constructor() {
  public class Exactly(vararg modifiers: KeyModifier) : ModifierMatch() {
    public val modifiers: Set<KeyModifier> = modifiers.toSet()

    override fun equals(other: kotlin.Any?): Boolean =
      other is Exactly && modifiers == other.modifiers

    override fun hashCode(): Int = modifiers.hashCode()

    override fun toString(): String = formatToString("Exactly", "modifiers" to modifiers)
  }

  public class Containing(vararg modifiers: KeyModifier) : ModifierMatch() {
    public val modifiers: Set<KeyModifier> = modifiers.toSet()

    override fun equals(other: kotlin.Any?): Boolean =
      other is Containing && modifiers == other.modifiers

    override fun hashCode(): Int = modifiers.hashCode()

    override fun toString(): String = formatToString("Containing", "modifiers" to modifiers)
  }

  internal fun matches(actual: Set<KeyModifier>): Boolean =
    when (this) {
      is Exactly -> actual == modifiers
      is Containing -> actual.containsAll(modifiers)
    }
}
