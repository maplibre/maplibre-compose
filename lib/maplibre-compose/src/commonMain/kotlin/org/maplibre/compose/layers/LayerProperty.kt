package org.maplibre.compose.layers

import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.style.StyleImageRequest
import org.maplibre.compose.style.internal.StyleValue

/** A property declaration; image references are resolved only after composition commits. */
internal class LayerProperty<out T : ExpressionValue?>(
  val images: Set<StyleImageRequest> = emptySet(),
  val resolve: (Map<StyleImageRequest, String>) -> StyleValue,
)
