package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.IlluminationAnchor
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toJsonString

internal actual class HillshadeLayer actual constructor(id: String, actual val source: Source) :
  Layer() {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val layerId: String = id
  override fun layerType(): String = "hillshade"
  override fun sourceId(): String = source.id

  actual fun setHillshadeIlluminationDirection(direction: CompiledExpression<FloatValue>) {
    setPaintProp("hillshade-illumination-direction", direction.toJsonString())
  }

  actual fun setHillshadeIlluminationAnchor(anchor: CompiledExpression<IlluminationAnchor>) {
    setPaintProp("hillshade-illumination-anchor", anchor.toJsonString())
  }

  actual fun setHillshadeExaggeration(exaggeration: CompiledExpression<FloatValue>) {
    setPaintProp("hillshade-exaggeration", exaggeration.toJsonString())
  }

  actual fun setHillshadeShadowColor(shadowColor: CompiledExpression<ColorValue>) {
    setPaintProp("hillshade-shadow-color", shadowColor.toJsonString())
  }

  actual fun setHillshadeHighlightColor(highlightColor: CompiledExpression<ColorValue>) {
    setPaintProp("hillshade-highlight-color", highlightColor.toJsonString())
  }

  actual fun setHillshadeAccentColor(accentColor: CompiledExpression<ColorValue>) {
    setPaintProp("hillshade-accent-color", accentColor.toJsonString())
  }
}
