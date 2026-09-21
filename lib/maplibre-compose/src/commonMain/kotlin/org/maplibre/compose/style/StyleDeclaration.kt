package org.maplibre.compose.style

import org.maplibre.compose.layers.LayerProperty

/** Immutable output of a committed composition, including unresolved image references. */
internal data class StyleDeclaration(
  val sources: List<SourceDefinition>,
  val layers: List<DeclaredStyleLayer>,
  val animatorDurationScale: Float = 1f,
  val fontScale: Float? = null,
)

internal data class DeclaredStyleLayer(
  val layer: DesiredStyleLayer,
  val imageProperties: Map<StyleProperty, LayerProperty<*>> = emptyMap(),
)

internal data class StyleProperty(val section: String?, val name: String)
