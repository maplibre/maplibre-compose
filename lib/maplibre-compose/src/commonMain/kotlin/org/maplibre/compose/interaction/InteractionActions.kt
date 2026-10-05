package org.maplibre.compose.interaction

/**
 * A built-in action selected by an input mapping. The library recognizes the input and executes the
 * action; applications cannot implement actions. Each binding accepts only the actions it can
 * execute, through [DragAction], [ScrollAction], [TapAction], or [KeyAction].
 *
 * Additional built-in actions may be introduced in future releases.
 */
public sealed interface InputAction {
  /**
   * Leaves matching input unclaimed and stops mapping selection. Later rows are not tried, even if
   * camera movements are disabled. Tap callbacks and feature click handlers still run.
   */
  public data object None : DragAction, ScrollAction, TapAction, KeyAction
}

/**
 * Actions available to a single-pointer drag: [CameraAction.Pan], [CameraAction.RotatePitch],
 * [CameraAction.FitBounds], and [InputAction.None].
 */
public sealed interface DragAction : InputAction

/**
 * Actions available to scrolling: [CameraAction.Pan], [CameraAction.Zoom], and [InputAction.None].
 */
public sealed interface ScrollAction : InputAction

/**
 * Actions available after tap callbacks and feature click handlers leave a tap unhandled:
 * [CameraAction.ZoomIn], [CameraAction.ZoomOut], and [InputAction.None].
 */
public sealed interface TapAction : InputAction

/**
 * Camera steps, [FocusAction] commands, and [InputAction.None] available to a key binding. Camera
 * actions require the map to be engaged. A camera key press moves one configured step; holding the
 * key continues movement. Focus commands execute on key press without camera motion.
 */
public sealed interface KeyAction : InputAction

/**
 * Built-in camera movements. The binding supplies thresholds, anchors, steps, and scaling;
 * [MapInteractions] determines which movements are permitted. Camera actions use the map's gesture
 * authority and apply only when its presentation is ready. Negative configured steps or gains
 * reverse the directions described below.
 */
public sealed interface CameraAction : InputAction {
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
 */
public sealed interface FocusAction : KeyAction {
  /** Engages the focused map so camera keys move it rather than traverse Compose focus. */
  public data object Engage : FocusAction

  /** Disengages the map. Unclaimed when it is already disengaged. */
  public data object Disengage : FocusAction

  /**
   * Disengages a map engaged through a key. Pointer engagement leaves Back unclaimed so the
   * application's navigation can handle it.
   */
  public data object Back : FocusAction
}
