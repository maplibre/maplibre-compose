package org.maplibre.compose.interaction

/** Actions shared by every input binding. */
public object InputAction {
  /**
   * Leaves matching input unclaimed and stops trying later mapping rows. Tap callbacks and feature
   * click handlers still run.
   */
  public data object None : DragAction, ScrollAction, TapAction, KeyAction
}

/** A built-in action for a single-pointer drag. */
public sealed interface DragAction

/** A built-in action for scrolling. */
public sealed interface ScrollAction

/**
 * A built-in action executed after tap callbacks and feature click handlers leave a tap unhandled.
 */
public sealed interface TapAction

/**
 * Camera steps, [FocusAction] commands, and [InputAction.None] available to a key binding. Camera
 * actions require the map to be engaged. A camera key press moves one configured step; holding the
 * key continues movement. Focus commands execute on key press without camera motion.
 */
public sealed interface KeyAction

/**
 * Built-in camera movements, restricted by the camera settings in [MapInteractions]. Movement
 * requires a ready map presentation. Negative configured steps or gains reverse these directions.
 */
public object CameraAction {
  /** Moves map content by drag or scroll displacement. */
  public data object Pan : DragAction, ScrollAction

  /** Rotates from horizontal dragging and pitches from vertical dragging. */
  public data object RotatePitch : DragAction

  /** Fits the geographic bounds of the dragged rectangle. Requires both pan and zoom. */
  public data object FitBounds : DragAction

  /** Zooms from vertical scrolling. Horizontal-only events remain unclaimed. */
  public data object Zoom : ScrollAction

  /** Increases zoom by the tap or key binding's zoom step. */
  public data object ZoomIn : TapAction, KeyAction

  /** Decreases zoom by the tap or key binding's zoom step. */
  public data object ZoomOut : TapAction, KeyAction

  /** Moves the camera left by the key binding's pan step. */
  public data object PanLeft : KeyAction

  /** Moves the camera right by the key binding's pan step. */
  public data object PanRight : KeyAction

  /** Moves the camera up by the key binding's pan step. */
  public data object PanUp : KeyAction

  /** Moves the camera down by the key binding's pan step. */
  public data object PanDown : KeyAction

  /** Decreases bearing by the key binding's rotate step. */
  public data object RotateLeft : KeyAction

  /** Increases bearing by the key binding's rotate step. */
  public data object RotateRight : KeyAction

  /** Increases pitch by the key binding's pitch step. */
  public data object PitchUp : KeyAction

  /** Decreases pitch by the key binding's pitch step. */
  public data object PitchDown : KeyAction
}

/**
 * Key commands for the focused map's engagement mode. They do not move the camera. Engagement
 * requires at least one reachable, permitted camera key mapping; removing those mappings or losing
 * Compose focus disengages the map.
 */
public object FocusAction {
  /** Engages the focused map so camera keys move it rather than traverse Compose focus. */
  public data object Engage : KeyAction

  /** Disengages the map. A new press is unclaimed when the map is already disengaged. */
  public data object Disengage : KeyAction

  /**
   * Disengages a map engaged through a key. Pointer engagement leaves Back unclaimed so the
   * application's navigation can handle it.
   */
  public data object Back : KeyAction
}

// Keeps caller matches non-exhaustive when new built-in actions are added.
internal object UnspecifiedAction : DragAction, ScrollAction, TapAction, KeyAction
