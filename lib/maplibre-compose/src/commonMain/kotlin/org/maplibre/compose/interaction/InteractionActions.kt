package org.maplibre.compose.interaction

import androidx.compose.runtime.Immutable

/**
 * Actions shared by every input binding.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface InputAction {
  /**
   * Leaves matching input unclaimed and stops trying later mapping rows. Tap callbacks and feature
   * click handlers still run.
   */
  public data object None : InputAction, DragAction, ScrollAction, TapAction, KeyAction
}

/**
 * A built-in action for a single-pointer drag.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable public sealed interface DragAction

/**
 * A built-in action for scrolling.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable public sealed interface ScrollAction

/**
 * A built-in action executed after tap callbacks and feature click handlers leave a tap unhandled.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable public sealed interface TapAction

/**
 * Camera steps, [FocusAction] commands, and [InputAction.None] available to a key binding. Camera
 * actions require the map to be engaged. A camera key press moves one configured step; holding the
 * key continues movement. Focus commands execute on key press without camera motion.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable public sealed interface KeyAction

/**
 * Built-in camera movements, restricted by the camera settings in [MapInteractions]. Movement
 * requires a ready map presentation. Negative configured steps or gains reverse these directions.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface CameraAction {
  /** Moves map content by drag or scroll displacement. */
  public data object Pan : CameraAction, DragAction, ScrollAction

  /** Rotates from horizontal dragging and pitches from vertical dragging. */
  public data object RotatePitch : CameraAction, DragAction

  /** Fits the geographic bounds of the dragged rectangle. Requires both pan and zoom. */
  public data object FitBounds : CameraAction, DragAction

  /** Zooms from vertical scrolling. Horizontal-only events remain unclaimed. */
  public data object Zoom : CameraAction, ScrollAction

  /** Increases zoom by the tap or key binding's zoom step. */
  public data object ZoomIn : CameraAction, TapAction, KeyAction

  /** Decreases zoom by the tap or key binding's zoom step. */
  public data object ZoomOut : CameraAction, TapAction, KeyAction

  /** Moves the camera left by the key binding's pan step. */
  public data object PanLeft : CameraAction, KeyAction

  /** Moves the camera right by the key binding's pan step. */
  public data object PanRight : CameraAction, KeyAction

  /** Moves the camera up by the key binding's pan step. */
  public data object PanUp : CameraAction, KeyAction

  /** Moves the camera down by the key binding's pan step. */
  public data object PanDown : CameraAction, KeyAction

  /** Decreases bearing by the key binding's rotate step. */
  public data object RotateLeft : CameraAction, KeyAction

  /** Increases bearing by the key binding's rotate step. */
  public data object RotateRight : CameraAction, KeyAction

  /** Increases pitch by the key binding's pitch step. */
  public data object PitchUp : CameraAction, KeyAction

  /** Decreases pitch by the key binding's pitch step. */
  public data object PitchDown : CameraAction, KeyAction
}

/**
 * Key commands for the focused map's engagement mode. They do not move the camera. Engagement
 * requires at least one reachable, permitted camera key mapping; removing those mappings or losing
 * Compose focus disengages the map.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface FocusAction {
  /** Engages the focused map so camera keys move it rather than traverse Compose focus. */
  public data object Engage : FocusAction, KeyAction

  /** Disengages the map. A new press is unclaimed when the map is already disengaged. */
  public data object Disengage : FocusAction, KeyAction

  /**
   * Disengages a map engaged through a key. Pointer engagement leaves Back unclaimed so the
   * application's navigation can handle it.
   */
  public data object Back : FocusAction, KeyAction
}

// Keeps caller matches non-exhaustive when new built-in actions are added.
internal object UnspecifiedAction :
  InputAction, CameraAction, FocusAction, DragAction, ScrollAction, TapAction, KeyAction
