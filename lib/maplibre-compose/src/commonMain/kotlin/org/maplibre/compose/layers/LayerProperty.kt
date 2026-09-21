package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.style.StyleImageRequest

/** A property declaration; image references are resolved only after composition commits. */
internal class LayerProperty<out T : ExpressionValue?>(
  val images: Set<StyleImageRequest> = emptySet(),
  val resolve: (Map<StyleImageRequest, String>) -> JsonElement,
)
