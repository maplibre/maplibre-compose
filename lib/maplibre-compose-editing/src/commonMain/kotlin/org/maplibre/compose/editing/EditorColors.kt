package org.maplibre.compose.editing

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Colors used by the editing layers. Defaults derive from [accent]. */
@Immutable
public class EditorColors(
  accent: Color = Color(0xFF285DAA),
  public val fill: Color = accent.copy(alpha = 0.15f),
  public val stroke: Color = accent,
  public val selectedFill: Color = accent.copy(alpha = 0.25f),
  public val selectedStroke: Color = accent,
  public val draft: Color = accent,
  public val handleFill: Color = Color.White,
  public val handleStroke: Color = accent,
  public val midpointFill: Color = accent.copy(alpha = 0.6f),
  public val activeHandleFill: Color = accent,
) {
  /** Returns a copy with the given colors replaced. */
  public fun copy(
    fill: Color = this.fill,
    stroke: Color = this.stroke,
    selectedFill: Color = this.selectedFill,
    selectedStroke: Color = this.selectedStroke,
    draft: Color = this.draft,
    handleFill: Color = this.handleFill,
    handleStroke: Color = this.handleStroke,
    midpointFill: Color = this.midpointFill,
    activeHandleFill: Color = this.activeHandleFill,
  ): EditorColors =
    EditorColors(
      fill = fill,
      stroke = stroke,
      selectedFill = selectedFill,
      selectedStroke = selectedStroke,
      draft = draft,
      handleFill = handleFill,
      handleStroke = handleStroke,
      midpointFill = midpointFill,
      activeHandleFill = activeHandleFill,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is EditorColors) return false
    return fill == other.fill &&
      stroke == other.stroke &&
      selectedFill == other.selectedFill &&
      selectedStroke == other.selectedStroke &&
      draft == other.draft &&
      handleFill == other.handleFill &&
      handleStroke == other.handleStroke &&
      midpointFill == other.midpointFill &&
      activeHandleFill == other.activeHandleFill
  }

  override fun hashCode(): Int {
    var result = fill.hashCode()
    result = 31 * result + stroke.hashCode()
    result = 31 * result + selectedFill.hashCode()
    result = 31 * result + selectedStroke.hashCode()
    result = 31 * result + draft.hashCode()
    result = 31 * result + handleFill.hashCode()
    result = 31 * result + handleStroke.hashCode()
    result = 31 * result + midpointFill.hashCode()
    result = 31 * result + activeHandleFill.hashCode()
    return result
  }

  override fun toString(): String =
    "EditorColors(fill=$fill, stroke=$stroke, selectedFill=$selectedFill, " +
      "selectedStroke=$selectedStroke, draft=$draft, handleFill=$handleFill, " +
      "handleStroke=$handleStroke, midpointFill=$midpointFill, " +
      "activeHandleFill=$activeHandleFill)"
}
