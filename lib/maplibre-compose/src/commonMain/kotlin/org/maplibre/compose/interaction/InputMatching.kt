package org.maplibre.compose.interaction

import androidx.compose.runtime.Immutable
import org.maplibre.compose.util.formatToString

/**
 * A physical mouse button. Touch and stylus match [Primary] without reporting a mouse button.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface PointerButton {
  /** The left mouse button. */
  public data object Primary : PointerButton

  /** The right mouse button. */
  public data object Secondary : PointerButton

  /** The middle mouse button, often the scroll wheel pressed down. */
  public data object Tertiary : PointerButton

  /** The side mouse button that navigates back. */
  public data object Back : PointerButton

  /** The side mouse button that navigates forward. */
  public data object Forward : PointerButton
}

/**
 * Keeps [PointerButton] open: callers' `when` needs an `else` branch. The library never reports it.
 */
internal data object UnspecifiedPointerButton : PointerButton

/**
 * A keyboard modifier key reported with an input sample.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface KeyModifier {
  /** The Shift key. */
  public data object Shift : KeyModifier

  /** The Control key. */
  public data object Ctrl : KeyModifier

  /** The Alt key, labeled Option on Apple keyboards. */
  public data object Alt : KeyModifier

  /** The Meta key: Command on Apple keyboards and the Windows key on others. */
  public data object Meta : KeyModifier
}

/**
 * Keeps [KeyModifier] open: callers' `when` needs an `else` branch. The library never reports it.
 */
internal data object UnspecifiedKeyModifier : KeyModifier

/** Every modifier this library reports, for code that enumerates modifier combinations. */
internal val ReportedKeyModifiers: List<KeyModifier> =
  listOf(KeyModifier.Shift, KeyModifier.Ctrl, KeyModifier.Alt, KeyModifier.Meta)

/**
 * Matches the complete modifier set or a subset. A null filter matches any modifiers.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed class ModifierMatch private constructor() {
  /** Matches when the pressed modifier keys are [modifiers] and no others. */
  public class Exactly(vararg modifiers: KeyModifier) : ModifierMatch() {
    public val modifiers: Set<KeyModifier> = modifiers.toSet()

    override fun equals(other: kotlin.Any?): Boolean =
      other is Exactly && modifiers == other.modifiers

    override fun hashCode(): Int = modifiers.hashCode()

    override fun toString(): String = formatToString("Exactly", "modifiers" to modifiers)
  }

  /** Matches when every key in [modifiers] is pressed, whatever other modifier keys are pressed. */
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
