package dev.sargunv.maplibrecompose.core.layer

import MapLibre.MLNFillExtrusionStyleLayer
import dev.sargunv.maplibrecompose.core.source.Source
import dev.sargunv.maplibrecompose.core.util.toNSExpression
import dev.sargunv.maplibrecompose.core.util.toNSPredicate
import dev.sargunv.maplibrecompose.expressions.ast.CompiledExpression
import dev.sargunv.maplibrecompose.expressions.ast.NullLiteral
import dev.sargunv.maplibrecompose.expressions.value.BooleanValue
import dev.sargunv.maplibrecompose.expressions.value.ColorValue
import dev.sargunv.maplibrecompose.expressions.value.DpOffsetValue
import dev.sargunv.maplibrecompose.expressions.value.FloatValue
import dev.sargunv.maplibrecompose.expressions.value.ImageValue
import dev.sargunv.maplibrecompose.expressions.value.TranslateAnchor

internal actual class FillExtrusionLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {

  override val impl = MLNFillExtrusionStyleLayer(id, source.impl)

  actual override var sourceLayer: String
    get() = impl.sourceLayerIdentifier!!
    set(value) {
      impl.sourceLayerIdentifier = value
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    impl.predicate = filter.toNSPredicate()
  }

  actual fun setFillExtrusionOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.fillExtrusionOpacity = opacity.toNSExpression()
  }

  actual fun setFillExtrusionColor(color: CompiledExpression<ColorValue>) {
    impl.fillExtrusionColor = color.toNSExpression()
  }

  actual fun setFillExtrusionTranslate(translate: CompiledExpression<DpOffsetValue>) {
    impl.fillExtrusionTranslation = translate.toNSExpression()
  }

  actual fun setFillExtrusionTranslateAnchor(anchor: CompiledExpression<TranslateAnchor>) {
    impl.fillExtrusionTranslationAnchor = anchor.toNSExpression()
  }

  actual fun setFillExtrusionPattern(pattern: CompiledExpression<ImageValue>) {
    // TODO figure out how to unset pattern
    if (pattern != NullLiteral) impl.fillExtrusionPattern = pattern.toNSExpression()
  }

  actual fun setFillExtrusionHeight(height: CompiledExpression<FloatValue>) {
    impl.fillExtrusionHeight = height.toNSExpression()
  }

  actual fun setFillExtrusionBase(base: CompiledExpression<FloatValue>) {
    impl.fillExtrusionBase = base.toNSExpression()
  }

  actual fun setFillExtrusionVerticalGradient(verticalGradient: CompiledExpression<BooleanValue>) {
    impl.fillExtrusionHasVerticalGradient = verticalGradient.toNSExpression()
  }
}
