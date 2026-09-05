package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.pointer.PointerType

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
public sealed class ModifierFilter private constructor() {
  public data object Any : ModifierFilter()

  public class Exactly(vararg modifiers: KeyModifier) : ModifierFilter() {
    public val modifiers: Set<KeyModifier> = modifiers.toSet()

    override fun equals(other: kotlin.Any?): Boolean =
      other is Exactly && modifiers == other.modifiers

    override fun hashCode(): Int = modifiers.hashCode()
  }

  public class Containing(vararg modifiers: KeyModifier) : ModifierFilter() {
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

/**
 * Selects reported pointer types, a button, and modifiers. Null types or button impose no
 * restriction. Every participating contact must match [pointerTypes].
 *
 * A touch or stylus contact counts as primary for contact gestures. Scroll requires a physical
 * button when [button] is non-null; use `PointerFilter(button = null)` for buttonless wheels.
 * Pointer types describe the host's report, not physical touchscreen or trackpad identity.
 */
@Immutable
public class PointerFilter(
  pointerTypes: Set<PointerType>? = null,
  public val button: PointerButton? = PointerButton.Primary,
  public val modifiers: ModifierFilter = ModifierFilter.Any,
) {
  public val pointerTypes: Set<PointerType>? = pointerTypes?.toSet()

  internal fun matches(
    types: Set<PointerType>,
    buttons: Set<PointerButton>,
    modifierKeys: Set<KeyModifier>,
    contact: Boolean,
    platformTransform: Boolean = false,
  ): Boolean =
    (pointerTypes?.let { allowed -> types.all { it in allowed } } != false) &&
      (button == null ||
        button in buttons ||
        (button == PointerButton.Primary &&
          contact &&
          (platformTransform && buttons.isEmpty() ||
            types.isNotEmpty() &&
              types.all {
                it == PointerType.Touch || it == PointerType.Stylus || it == PointerType.Eraser
              }))) &&
      modifiers.matches(modifierKeys)

  override fun equals(other: Any?): Boolean =
    other is PointerFilter &&
      pointerTypes == other.pointerTypes &&
      button == other.button &&
      modifiers == other.modifiers

  override fun hashCode(): Int =
    31 * (31 * (pointerTypes?.hashCode() ?: 0) + (button?.hashCode() ?: 0)) + modifiers.hashCode()
}
