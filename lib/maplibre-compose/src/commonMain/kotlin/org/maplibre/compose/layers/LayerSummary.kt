package org.maplibre.compose.layers

import androidx.compose.runtime.Immutable

/** Metadata fixed for a layer in one loaded style, without access to the engine. */
@Immutable
public data class LayerSummary
internal constructor(
  /** The layer ID. */
  public val id: String,
  /** The style-spec layer type. */
  public val type: String,
  /** The source ID, or null for a layer without a source. */
  public val source: String?,
  /** The vector source layer, or null when none is selected. */
  public val sourceLayer: String?,
)
