package org.maplibre.compose.map

import org.maplibre.compose.style.BaseStyle

/**
 * The load progress of the style [MapState] last selected.
 *
 * A new [MapState.baseStyle] assignment starts a new generation. Events from an earlier generation
 * never change this value.
 */
public sealed interface MapLoadState {
  /** No style has been selected yet. */
  public data object Idle : MapLoadState

  /** [style] is the current selection and has not finished loading. */
  public data class Loading(public val generation: Long, public val style: BaseStyle) : MapLoadState

  /** [style] is the current selection and has finished loading. */
  public data class Ready(public val generation: Long, public val style: BaseStyle) : MapLoadState

  /** [style] is the current selection and failed to load. */
  public data class Failed(
    public val generation: Long,
    public val style: BaseStyle,
    public val reason: String,
  ) : MapLoadState
}
