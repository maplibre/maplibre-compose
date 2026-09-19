package org.maplibre.compose.editing

import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Placeholder with the designed constructor; the select-tool stage replaces this file.

/** Selects features and edits vertices. */
public class SelectTool(
  public val canSelect: (EditorFeature) -> Boolean = { true },
  public val moveSelected: (PointerType) -> Boolean = { it != PointerType.Touch },
  public val editVertices: (EditorFeature) -> Boolean = { true },
  public val midpoints: (EditorFeature) -> Boolean = { true },
  public val removeVertexOnSecondaryClick: Boolean = true,
  public val removeSelectionOnDelete: Boolean = true,
  public val clearSelectionOnEmptyTap: Boolean = true,
  public val nudgeStep: Dp? = 1.dp,
  public val handleLimit: Int = 5000,
) : EditorTool {
  override fun onEvent(event: EditorEvent, state: FeatureEditorState): Boolean = false
}
