package org.maplibre.compose.editing

import kotlinx.serialization.json.JsonObject

// Placeholder with the designed constructor; the draw-tool stage replaces this file. The saver
// reads these properties and calls this constructor through SavedDrawTool.

/** Draws a feature of [shape]. */
public class DrawTool(
  public val shape: DrawShape,
  public val properties: JsonObject? = null,
  public val nextTool: EditorTool? = SelectTool(),
  public val finishOnDoubleTap: Boolean = true,
  public val finishOnVertexTap: Boolean = true,
  public val replaceExisting: Boolean = false,
  public val placeOnTap: Boolean = true,
  public val draftHandles: Boolean = true,
) : EditorTool {
  override fun onEvent(event: EditorEvent, state: FeatureEditorState): Boolean = false
}
