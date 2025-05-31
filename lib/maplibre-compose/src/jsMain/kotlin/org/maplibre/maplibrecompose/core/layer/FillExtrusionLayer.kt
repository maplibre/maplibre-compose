package org.maplibre.maplibrecompose.core.layer

import org.maplibre.maplibrecompose.core.source.Source
import org.maplibre.maplibrecompose.expressions.ast.CompiledExpression
import org.maplibre.maplibrecompose.expressions.value.*

internal actual class FillExtrusionLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  override val impl = TODO()

  actual override var sourceLayer: String = TODO()

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    TODO()
  }

  actual fun setFillExtrusionOpacity(opacity: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setFillExtrusionColor(color: CompiledExpression<ColorValue>) {
    TODO()
  }

  actual fun setFillExtrusionTranslate(translate: CompiledExpression<DpOffsetValue>) {
    TODO()
  }

  actual fun setFillExtrusionTranslateAnchor(anchor: CompiledExpression<TranslateAnchor>) {
    TODO()
  }

  actual fun setFillExtrusionPattern(pattern: CompiledExpression<ImageValue>) {
    TODO()
  }

  actual fun setFillExtrusionHeight(height: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setFillExtrusionBase(base: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setFillExtrusionVerticalGradient(verticalGradient: CompiledExpression<BooleanValue>) {
    TODO()
  }
}
