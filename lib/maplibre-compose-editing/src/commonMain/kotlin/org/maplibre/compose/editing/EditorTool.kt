package org.maplibre.compose.editing

import androidx.compose.runtime.Stable
import androidx.compose.ui.input.pointer.PointerIcon
import org.maplibre.spatialk.geojson.FeatureId
import org.maplibre.spatialk.geojson.Position

/**
 * Interprets editor input and mutates a [FeatureEditorState].
 *
 * The state's draft functions dispatch to [FeatureEditorState.tool]. A tool may hold snapshot state
 * of its own; it is not saved with the state. The built-in tools hold none and use
 * [FeatureEditorState.activeHandle], [FeatureEditorState.draft], [FeatureEditorState.featureBefore]
 * and event steps.
 */
@Stable
public interface EditorTool {
  /** Handles to render and hit-test. Read inside a derived state. */
  public fun handles(state: FeatureEditorState): List<EditorHandle> = emptyList()

  /**
   * Handles one event. The result claims the pointer for [EditorEvent.Press], consumes
   * [EditorEvent.Tap] and [EditorEvent.Key], and is ignored for other events.
   */
  public fun onEvent(event: EditorEvent, state: FeatureEditorState): Boolean

  /** Cursor shown over the map. */
  public fun cursor(state: FeatureEditorState): PointerIcon = PointerIcon.Default

  /** Adds [position] to the draft as a tap would. Returns false when this tool does not draw. */
  public fun placeDraftPosition(state: FeatureEditorState, position: Position): Boolean = false

  /** Whether [finishDraft] would create a feature. */
  public fun canFinishDraft(state: FeatureEditorState): Boolean = false

  /** Commits the draft as a feature and returns its id, or null when nothing was created. */
  public fun finishDraft(state: FeatureEditorState): FeatureId? = null
}
