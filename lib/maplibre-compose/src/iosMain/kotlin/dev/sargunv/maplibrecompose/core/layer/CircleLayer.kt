package dev.sargunv.maplibrecompose.core.layer

import MapLibre.MLNCircleStyleLayer
import dev.sargunv.maplibrecompose.core.source.Source
import dev.sargunv.maplibrecompose.core.util.toNSExpression
import dev.sargunv.maplibrecompose.core.util.toNSPredicate
import dev.sargunv.maplibrecompose.expressions.ast.CompiledExpression
import dev.sargunv.maplibrecompose.expressions.value.BooleanValue
import dev.sargunv.maplibrecompose.expressions.value.CirclePitchAlignment
import dev.sargunv.maplibrecompose.expressions.value.CirclePitchScale
import dev.sargunv.maplibrecompose.expressions.value.ColorValue
import dev.sargunv.maplibrecompose.expressions.value.DpOffsetValue
import dev.sargunv.maplibrecompose.expressions.value.DpValue
import dev.sargunv.maplibrecompose.expressions.value.FloatValue
import dev.sargunv.maplibrecompose.expressions.value.TranslateAnchor

internal actual class CircleLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {

  override val impl = MLNCircleStyleLayer(id, source.impl)

  actual override var sourceLayer: String
    get() = impl.sourceLayerIdentifier!!
    set(value) {
      impl.sourceLayerIdentifier = value
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    impl.predicate = filter.toNSPredicate()
  }

  actual fun setCircleSortKey(sortKey: CompiledExpression<FloatValue>) {
    impl.circleSortKey = sortKey.toNSExpression()
  }

  actual fun setCircleRadius(radius: CompiledExpression<DpValue>) {
    impl.circleRadius = radius.toNSExpression()
  }

  actual fun setCircleColor(color: CompiledExpression<ColorValue>) {
    impl.circleColor = color.toNSExpression()
  }

  actual fun setCircleBlur(blur: CompiledExpression<FloatValue>) {
    impl.circleBlur = blur.toNSExpression()
  }

  actual fun setCircleOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.circleOpacity = opacity.toNSExpression()
  }

  actual fun setCircleTranslate(translate: CompiledExpression<DpOffsetValue>) {
    impl.circleTranslation = translate.toNSExpression()
  }

  actual fun setCircleTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    impl.circleTranslationAnchor = translateAnchor.toNSExpression()
  }

  actual fun setCirclePitchScale(pitchScale: CompiledExpression<CirclePitchScale>) {
    impl.circleScaleAlignment = pitchScale.toNSExpression()
  }

  actual fun setCirclePitchAlignment(pitchAlignment: CompiledExpression<CirclePitchAlignment>) {
    impl.circlePitchAlignment = pitchAlignment.toNSExpression()
  }

  actual fun setCircleStrokeWidth(strokeWidth: CompiledExpression<DpValue>) {
    impl.circleStrokeWidth = strokeWidth.toNSExpression()
  }

  actual fun setCircleStrokeColor(strokeColor: CompiledExpression<ColorValue>) {
    impl.circleStrokeColor = strokeColor.toNSExpression()
  }

  actual fun setCircleStrokeOpacity(strokeOpacity: CompiledExpression<FloatValue>) {
    impl.circleStrokeOpacity = strokeOpacity.toNSExpression()
  }
}
