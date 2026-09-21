package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.style.StyleImageRequest
import org.maplibre.compose.util.toStyleJson

/** A property declaration; image references are resolved only after composition commits. */
internal class LayerProperty<out T : ExpressionValue?>(
  val images: Set<StyleImageRequest> = emptySet(),
  val resolve: (Map<StyleImageRequest, String>) -> JsonElement,
) {
  @Suppress("UNCHECKED_CAST")
  fun <X : ExpressionValue?> cast(): LayerProperty<X> = this as LayerProperty<X>
}

internal fun <T : ExpressionValue?> CompiledExpression<T>.asLayerProperty(): LayerProperty<T> =
  LayerProperty {
    toStyleJson()
  }
