package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.IlluminationAnchor
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toJsonString
import org.maplibre.kmp.native.style.layers.HillshadeLayer as MLNHillshadeLayer

internal actual class HillshadeLayer actual constructor(id: String, actual val source: Source) :
  Layer() {
  override val impl = MLNHillshadeLayer(id, source.id)

  actual fun setHillshadeIlluminationDirection(direction: CompiledExpression<FloatValue>) {
    direction.toJsonString()?.let { impl.setProperty("hillshade-illumination-direction", it) }
  }

  actual fun setHillshadeIlluminationAnchor(anchor: CompiledExpression<IlluminationAnchor>) {
    anchor.toJsonString()?.let { impl.setProperty("hillshade-illumination-anchor", it) }
  }

  actual fun setHillshadeExaggeration(exaggeration: CompiledExpression<FloatValue>) {
    exaggeration.toJsonString()?.let { impl.setProperty("hillshade-exaggeration", it) }
  }

  actual fun setHillshadeShadowColor(shadowColor: CompiledExpression<ColorValue>) {
    shadowColor.toJsonString()?.let { impl.setProperty("hillshade-shadow-color", it) }
  }

  actual fun setHillshadeHighlightColor(highlightColor: CompiledExpression<ColorValue>) {
    highlightColor.toJsonString()?.let { impl.setProperty("hillshade-highlight-color", it) }
  }

  actual fun setHillshadeAccentColor(accentColor: CompiledExpression<ColorValue>) {
    accentColor.toJsonString()?.let { impl.setProperty("hillshade-accent-color", it) }
  }
}
