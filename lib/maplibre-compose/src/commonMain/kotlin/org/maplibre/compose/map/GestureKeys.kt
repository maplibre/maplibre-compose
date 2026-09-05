package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** An exact key and modifier combination. Additional modifiers prevent a match. */
@Immutable
public class KeyChord(public val key: Key, vararg modifiers: KeyModifier) {
  public val modifiers: Set<KeyModifier> = modifiers.toSet()

  override fun equals(other: Any?): Boolean =
    other is KeyChord && key == other.key && modifiers == other.modifiers

  override fun hashCode(): Int = 31 * key.hashCode() + modifiers.hashCode()
}

/** A keyboard camera step or engagement transition. [Back] only exits engagement begun by a key. */
public enum class GestureKeyAction {
  PanLeft,
  PanRight,
  PanUp,
  PanDown,
  ZoomIn,
  ZoomOut,
  RotateLeft,
  RotateRight,
  TiltUp,
  TiltDown,
  Engage,
  Disengage,
  Back;

  internal val isCamera: Boolean
    get() = this != Engage && this != Disengage && this != Back
}

/**
 * Exact keyboard chords and independent focused rotary zoom. Clearing the last camera chord removes
 * keyboard engagement handling; rotary alone still permits focus.
 */
public class GestureKeysBuilder internal constructor(from: GestureKeyBindings) {
  private val bindings = from.chords.toMutableMap()
  private val rotaryBuilder = RotaryZoomBuilder(from.rotary)
  private var observer = from.onEvent
  public var panStep: Dp = from.panStep
  public var zoomStep: Double = from.zoomStep
  public var rotateStep: Double = from.rotateStep
  public var pitchStep: Double = from.pitchStep

  /** Assigns or replaces the one action for this exact chord. */
  public fun bind(chord: KeyChord, action: GestureKeyAction) {
    bindings[chord] = action
  }

  public fun remove(chord: KeyChord) {
    bindings.remove(chord)
  }

  public fun clear() {
    bindings.clear()
  }

  public fun clearPan() {
    removeActions(
      setOf(
        GestureKeyAction.PanLeft,
        GestureKeyAction.PanRight,
        GestureKeyAction.PanUp,
        GestureKeyAction.PanDown,
      )
    )
  }

  public fun clearZoom() {
    removeActions(setOf(GestureKeyAction.ZoomIn, GestureKeyAction.ZoomOut))
  }

  public fun clearRotate() {
    removeActions(setOf(GestureKeyAction.RotateLeft, GestureKeyAction.RotateRight))
  }

  public fun clearTilt() {
    removeActions(setOf(GestureKeyAction.TiltUp, GestureKeyAction.TiltDown))
  }

  public fun onEvent(block: ((KeyGestureEvent) -> Unit)?) {
    observer = block
  }

  public fun rotaryZoom(block: RotaryZoomBuilder.() -> Unit) {
    rotaryBuilder.apply(block)
  }

  private fun removeActions(actions: Set<GestureKeyAction>) {
    bindings.entries.removeAll { it.value in actions }
  }

  internal fun build(): GestureKeyBindings {
    require(panStep.value.isFinite()) { "Keyboard panStep must be finite" }
    listOf(zoomStep, rotateStep, pitchStep).forEach {
      require(it.isFinite()) { "Keyboard steps must be finite" }
    }
    return GestureKeyBindings(
      bindings.toMap(),
      panStep,
      zoomStep,
      rotateStep,
      pitchStep,
      rotaryBuilder.build(),
      observer,
    )
  }
}

/** Rotary zoom uses the camera center and does not require keyboard engagement. */
public class RotaryZoomBuilder internal constructor(from: RotaryZoomBinding) {
  public var enabled: Boolean = from.enabled
  public var zoomStep: Double = from.zoomStep
  public var idleDuration: Duration = from.idleDuration
  private var observer = from.onEvent

  public fun onEvent(block: ((RotaryGestureEvent) -> Unit)?) {
    observer = block
  }

  internal fun build(): RotaryZoomBinding {
    require(zoomStep.isFinite()) { "Rotary zoomStep must be finite" }
    requireNonnegativeFinite(idleDuration, "Rotary idleDuration")
    return RotaryZoomBinding(enabled, zoomStep, idleDuration, observer)
  }
}

internal data class RotaryZoomBinding(
  val enabled: Boolean = true,
  val zoomStep: Double = 0.15,
  val idleDuration: Duration = 200.milliseconds,
  val onEvent: ((RotaryGestureEvent) -> Unit)? = null,
) {
  val structuralKey: Any
    get() = listOf(enabled, zoomStep, idleDuration, onEvent != null)
}

internal data class GestureKeyBindings(
  val chords: Map<KeyChord, GestureKeyAction>,
  val panStep: Dp = 100.dp,
  val zoomStep: Double = 1.0,
  val rotateStep: Double = 15.0,
  val pitchStep: Double = 10.0,
  val rotary: RotaryZoomBinding = RotaryZoomBinding(),
  val onEvent: ((KeyGestureEvent) -> Unit)? = null,
) {
  val hasCameraBindings: Boolean
    get() = chords.values.any { it.isCamera }

  val structuralKey: Any
    get() =
      listOf(
        chords,
        panStep,
        zoomStep,
        rotateStep,
        pitchStep,
        rotary.structuralKey,
        onEvent != null,
      )

  companion object {
    fun none(): GestureKeyBindings =
      GestureKeyBindings(emptyMap(), rotary = RotaryZoomBinding(enabled = false))

    fun standard(): GestureKeyBindings =
      GestureKeyBindings(
        linkedMapOf(
          KeyChord(Key.DirectionLeft) to GestureKeyAction.PanLeft,
          KeyChord(Key.DirectionRight) to GestureKeyAction.PanRight,
          KeyChord(Key.DirectionUp) to GestureKeyAction.PanUp,
          KeyChord(Key.DirectionDown) to GestureKeyAction.PanDown,
          KeyChord(Key.DirectionLeft, KeyModifier.Shift) to GestureKeyAction.RotateLeft,
          KeyChord(Key.DirectionRight, KeyModifier.Shift) to GestureKeyAction.RotateRight,
          KeyChord(Key.DirectionUp, KeyModifier.Shift) to GestureKeyAction.TiltUp,
          KeyChord(Key.DirectionDown, KeyModifier.Shift) to GestureKeyAction.TiltDown,
          KeyChord(Key.Plus) to GestureKeyAction.ZoomIn,
          KeyChord(Key.Equals) to GestureKeyAction.ZoomIn,
          KeyChord(Key.Plus, KeyModifier.Shift) to GestureKeyAction.ZoomIn,
          KeyChord(Key.Equals, KeyModifier.Shift) to GestureKeyAction.ZoomIn,
          KeyChord(Key.Minus) to GestureKeyAction.ZoomOut,
          KeyChord(Key.Enter) to GestureKeyAction.Engage,
          KeyChord(Key.NumPadEnter) to GestureKeyAction.Engage,
          KeyChord(Key.DirectionCenter) to GestureKeyAction.Engage,
          KeyChord(Key.Escape) to GestureKeyAction.Disengage,
          KeyChord(Key.Back) to GestureKeyAction.Back,
        )
      )
  }
}
