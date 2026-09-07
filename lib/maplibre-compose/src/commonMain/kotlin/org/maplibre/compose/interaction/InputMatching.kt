package org.maplibre.compose.interaction

import androidx.compose.runtime.Immutable

/** A physical mouse button. Touch and stylus match [Primary] without reporting a mouse button. */
public enum class PointerButton {
  Primary,
  Secondary,
  Tertiary,
  Back,
  Forward,
}

/** Keyboard modifiers reported with an input sample. */
public enum class KeyModifier {
  Shift,
  Ctrl,
  Alt,
  Meta,
}

/** Matches the complete modifier set, a subset, or any modifiers. */
@Immutable
public sealed class ModifierMatch private constructor() {
  public data object Any : ModifierMatch()

  public class Exactly(vararg modifiers: KeyModifier) : ModifierMatch() {
    public val modifiers: Set<KeyModifier> = modifiers.toSet()

    override fun equals(other: kotlin.Any?): Boolean =
      other is Exactly && modifiers == other.modifiers

    override fun hashCode(): Int = modifiers.hashCode()
  }

  public class Containing(vararg modifiers: KeyModifier) : ModifierMatch() {
    public val modifiers: Set<KeyModifier> = modifiers.toSet()

    override fun equals(other: kotlin.Any?): Boolean =
      other is Containing && modifiers == other.modifiers

    override fun hashCode(): Int = modifiers.hashCode()
  }

  internal fun matches(actual: Set<KeyModifier>): Boolean =
    when (this) {
      Any -> true
      is Exactly -> actual == modifiers
      is Containing -> actual.containsAll(modifiers)
    }
}
