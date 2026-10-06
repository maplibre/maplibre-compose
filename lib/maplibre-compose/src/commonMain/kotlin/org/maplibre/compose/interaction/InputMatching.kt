package org.maplibre.compose.interaction

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline
import org.maplibre.compose.util.formatToString

/**
 * A physical mouse button. Touch and stylus match [Primary] without reporting a mouse button.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class PointerButton private constructor(private val name: String) {
  override fun toString(): String = name

  public companion object {
    /** The left mouse button. */
    public val Primary: PointerButton = PointerButton("Primary")

    /** The right mouse button. */
    public val Secondary: PointerButton = PointerButton("Secondary")

    /** The middle mouse button, often the scroll wheel pressed down. */
    public val Tertiary: PointerButton = PointerButton("Tertiary")

    /** The side mouse button that navigates back. */
    public val Back: PointerButton = PointerButton("Back")

    /** The side mouse button that navigates forward. */
    public val Forward: PointerButton = PointerButton("Forward")
  }
}

/**
 * A keyboard modifier key reported with an input sample.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class KeyModifier private constructor(private val name: String) {
  override fun toString(): String = name

  public companion object {
    /** The Shift key. */
    public val Shift: KeyModifier = KeyModifier("Shift")

    /** The Control key. */
    public val Ctrl: KeyModifier = KeyModifier("Ctrl")

    /** The Alt key, labeled Option on Apple keyboards. */
    public val Alt: KeyModifier = KeyModifier("Alt")

    /** The Meta key: Command on Apple keyboards and the Windows key on others. */
    public val Meta: KeyModifier = KeyModifier("Meta")

    /** Every modifier this library reports, for code that enumerates modifier combinations. */
    internal val entries: List<KeyModifier> = listOf(Shift, Ctrl, Alt, Meta)
  }
}

/**
 * Matches the complete modifier set or a subset. A null filter matches any modifiers.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed class ModifierMatch private constructor() {
  /** Matches when the pressed modifier keys are [modifiers] and no others. */
  public class Exactly(modifiers: Set<KeyModifier> = emptySet()) : ModifierMatch() {
    public val modifiers: Set<KeyModifier> = modifiers.toSet()

    override fun equals(other: kotlin.Any?): Boolean =
      other is Exactly && modifiers == other.modifiers

    override fun hashCode(): Int = modifiers.hashCode()

    override fun toString(): String = formatToString("Exactly", "modifiers" to modifiers)
  }

  /** Matches when every key in [modifiers] is pressed, whatever other modifier keys are pressed. */
  public class Containing(modifiers: Set<KeyModifier>) : ModifierMatch() {
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
