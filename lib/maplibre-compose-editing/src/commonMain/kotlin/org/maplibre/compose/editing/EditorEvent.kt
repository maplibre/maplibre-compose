package org.maplibre.compose.editing

import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.spatialk.geojson.Position

/**
 * A pointer sample. [screen] is dp from the map's top-left corner. [position] longitude is wrapped
 * to [-180, 180].
 */
public data class EditorPointer(
  val screen: DpOffset,
  val position: Position,
  val pointerType: PointerType,
  /** Buttons held at the press. Empty for touch. */
  val buttons: Set<PointerButton>,
  val modifierKeys: Set<KeyModifier>,
)

/**
 * Input delivered to an [EditorTool].
 *
 * Every event carries the projection of the map that delivered it. [project] and [unproject] return
 * null while the map has no viewport; [unproject] also returns null for a screen location off the
 * globe. Positions off screen still project; cull with [FeatureEditorState.visibleBounds] before
 * projecting many. Subtypes may be added; handle unknown events with an `else` branch. Equality of
 * events includes the projection functions and is not meaningful.
 */
public interface EditorEvent {
  public val project: (Position) -> DpOffset?
  public val unproject: (DpOffset) -> Position?

  /**
   * A pointer went down. Returning true claims the pointer. [step] identifies the press; pass it as
   * `undoStep`.
   */
  public data class Press(
    val pointer: EditorPointer,
    val hit: EditorHit?,
    val step: EditStep,
    override val project: (Position) -> DpOffset?,
    override val unproject: (DpOffset) -> Position?,
  ) : EditorEvent

  /**
   * A claimed pointer moved past slop. [origin] is the press, [previous] the last delivered sample,
   * [hit] the press hit.
   */
  public data class Drag(
    val pointer: EditorPointer,
    val origin: EditorPointer,
    val previous: EditorPointer,
    val hit: EditorHit?,
    val step: EditStep,
    override val project: (Position) -> DpOffset?,
    override val unproject: (DpOffset) -> Position?,
  ) : EditorEvent

  /** A claimed pointer lifted after dragging, or a second pointer landed during the drag. */
  public data class Release(
    val pointer: EditorPointer,
    val step: EditStep,
    override val project: (Position) -> DpOffset?,
    override val unproject: (DpOffset) -> Position?,
  ) : EditorEvent

  /**
   * A pointer lifted within slop. [count] is 2 for the second tap of a double tap. Returning true
   * consumes it.
   */
  public data class Tap(
    val pointer: EditorPointer,
    val hit: EditorHit?,
    val count: Int,
    val step: EditStep,
    override val project: (Position) -> DpOffset?,
    override val unproject: (DpOffset) -> Position?,
  ) : EditorEvent

  /**
   * A claimed touch or stylus pointer was held past the long press timeout without moving.
   * Returning true ends the gesture: the pointer is ignored until it lifts and no [Drag], [Release]
   * or [Tap] follows. Returning false keeps the pointer claimed, so a later move emits [Drag] and a
   * lift emits [Release] or [Tap].
   */
  public data class LongPress(
    val pointer: EditorPointer,
    val hit: EditorHit?,
    val step: EditStep,
    override val project: (Position) -> DpOffset?,
    override val unproject: (DpOffset) -> Position?,
  ) : EditorEvent

  /**
   * A claimed pointer was lost, Escape was pressed, a second pointer landed before any drag, or the
   * modifier was disabled.
   */
  public data class Cancel(
    val step: EditStep,
    override val project: (Position) -> DpOffset?,
    override val unproject: (DpOffset) -> Position?,
  ) : EditorEvent

  /** An unpressed mouse pointer moved. */
  public data class Hover(
    val pointer: EditorPointer,
    val hit: EditorHit?,
    override val project: (Position) -> DpOffset?,
    override val unproject: (DpOffset) -> Position?,
  ) : EditorEvent

  /** The mouse pointer left the map after a [Hover], or the editor was disabled. */
  public data class HoverEnd(
    override val project: (Position) -> DpOffset?,
    override val unproject: (DpOffset) -> Position?,
  ) : EditorEvent

  /**
   * A key event while the map has focus. A held key repeats as KeyDown events. Returning true
   * consumes it before the map's bindings.
   */
  public data class Key(
    val key: androidx.compose.ui.input.key.Key,
    val type: KeyEventType,
    val modifierKeys: Set<KeyModifier>,
    override val project: (Position) -> DpOffset?,
    override val unproject: (DpOffset) -> Position?,
  ) : EditorEvent
}
