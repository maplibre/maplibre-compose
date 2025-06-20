package dev.sargunv.maplibrecompose.core.layer

import dev.sargunv.maplibrecompose.core.source.Source
import dev.sargunv.maplibrecompose.core.util.toMLNExpression
import dev.sargunv.maplibrecompose.expressions.ast.CompiledExpression
import dev.sargunv.maplibrecompose.expressions.value.BooleanValue
import dev.sargunv.maplibrecompose.expressions.value.ColorValue
import dev.sargunv.maplibrecompose.expressions.value.DpOffsetValue
import dev.sargunv.maplibrecompose.expressions.value.DpPaddingValue
import dev.sargunv.maplibrecompose.expressions.value.DpValue
import dev.sargunv.maplibrecompose.expressions.value.FloatOffsetValue
import dev.sargunv.maplibrecompose.expressions.value.FloatValue
import dev.sargunv.maplibrecompose.expressions.value.FormattedValue
import dev.sargunv.maplibrecompose.expressions.value.IconPitchAlignment
import dev.sargunv.maplibrecompose.expressions.value.IconRotationAlignment
import dev.sargunv.maplibrecompose.expressions.value.IconTextFit
import dev.sargunv.maplibrecompose.expressions.value.ImageValue
import dev.sargunv.maplibrecompose.expressions.value.ListValue
import dev.sargunv.maplibrecompose.expressions.value.StringValue
import dev.sargunv.maplibrecompose.expressions.value.SymbolAnchor
import dev.sargunv.maplibrecompose.expressions.value.SymbolOverlap
import dev.sargunv.maplibrecompose.expressions.value.SymbolPlacement
import dev.sargunv.maplibrecompose.expressions.value.SymbolZOrder
import dev.sargunv.maplibrecompose.expressions.value.TextJustify
import dev.sargunv.maplibrecompose.expressions.value.TextPitchAlignment
import dev.sargunv.maplibrecompose.expressions.value.TextRotationAlignment
import dev.sargunv.maplibrecompose.expressions.value.TextTransform
import dev.sargunv.maplibrecompose.expressions.value.TextVariableAnchorOffsetValue
import dev.sargunv.maplibrecompose.expressions.value.TextWritingMode
import dev.sargunv.maplibrecompose.expressions.value.TranslateAnchor
import org.maplibre.android.style.expressions.Expression as MLNExpression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer as MLNSymbolLayer

internal actual class SymbolLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  override val impl = MLNSymbolLayer(id, source.id)

  actual override var sourceLayer: String by impl::sourceLayer

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    impl.setFilter(filter.toMLNExpression() ?: MLNExpression.literal(true))
  }

  actual fun setSymbolPlacement(placement: CompiledExpression<SymbolPlacement>) {
    impl.setProperties(PropertyFactory.symbolPlacement(placement.toMLNExpression()))
  }

  actual fun setSymbolSpacing(spacing: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.symbolSpacing(spacing.toMLNExpression()))
  }

  actual fun setSymbolAvoidEdges(avoidEdges: CompiledExpression<BooleanValue>) {
    impl.setProperties(PropertyFactory.symbolAvoidEdges(avoidEdges.toMLNExpression()))
  }

  actual fun setSymbolSortKey(sortKey: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.symbolSortKey(sortKey.toMLNExpression()))
  }

  actual fun setSymbolZOrder(zOrder: CompiledExpression<SymbolZOrder>) {
    impl.setProperties(PropertyFactory.symbolZOrder(zOrder.toMLNExpression()))
  }

  actual fun setIconAllowOverlap(allowOverlap: CompiledExpression<BooleanValue>) {
    impl.setProperties(PropertyFactory.iconAllowOverlap(allowOverlap.toMLNExpression()))
  }

  actual fun setIconOverlap(overlap: CompiledExpression<StringValue>) {
    // TODO: warn not implemented by MapLibre-native Android yet
    // impl.setProperties(PropertyFactory.iconOverlap(overlap.toMLNExpression()))
  }

  actual fun setIconIgnorePlacement(ignorePlacement: CompiledExpression<BooleanValue>) {
    impl.setProperties(PropertyFactory.iconIgnorePlacement(ignorePlacement.toMLNExpression()))
  }

  actual fun setIconOptional(optional: CompiledExpression<BooleanValue>) {
    impl.setProperties(PropertyFactory.iconOptional(optional.toMLNExpression()))
  }

  actual fun setIconRotationAlignment(
    rotationAlignment: CompiledExpression<IconRotationAlignment>
  ) {
    impl.setProperties(PropertyFactory.iconRotationAlignment(rotationAlignment.toMLNExpression()))
  }

  actual fun setIconSize(size: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.iconSize(size.toMLNExpression()))
  }

  actual fun setIconTextFit(textFit: CompiledExpression<IconTextFit>) {
    impl.setProperties(PropertyFactory.iconTextFit(textFit.toMLNExpression()))
  }

  actual fun setIconTextFitPadding(textFitPadding: CompiledExpression<DpPaddingValue>) {
    impl.setProperties(PropertyFactory.iconTextFitPadding(textFitPadding.toMLNExpression()))
  }

  actual fun setIconImage(image: CompiledExpression<ImageValue>) {
    impl.setProperties(PropertyFactory.iconImage(image.toMLNExpression()))
  }

  actual fun setIconRotate(rotate: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.iconRotate(rotate.toMLNExpression()))
  }

  actual fun setIconPadding(padding: CompiledExpression<DpPaddingValue>) {
    impl.setProperties(PropertyFactory.iconPadding(padding.toMLNExpression()))
  }

  actual fun setIconKeepUpright(keepUpright: CompiledExpression<BooleanValue>) {
    impl.setProperties(PropertyFactory.iconKeepUpright(keepUpright.toMLNExpression()))
  }

  actual fun setIconOffset(offset: CompiledExpression<DpOffsetValue>) {
    impl.setProperties(PropertyFactory.iconOffset(offset.toMLNExpression()))
  }

  actual fun setIconAnchor(anchor: CompiledExpression<SymbolAnchor>) {
    impl.setProperties(PropertyFactory.iconAnchor(anchor.toMLNExpression()))
  }

  actual fun setIconPitchAlignment(pitchAlignment: CompiledExpression<IconPitchAlignment>) {
    impl.setProperties(PropertyFactory.iconPitchAlignment(pitchAlignment.toMLNExpression()))
  }

  actual fun setIconOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.iconOpacity(opacity.toMLNExpression()))
  }

  actual fun setIconColor(color: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.iconColor(color.toMLNExpression()))
  }

  actual fun setIconHaloColor(haloColor: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.iconHaloColor(haloColor.toMLNExpression()))
  }

  actual fun setIconHaloWidth(haloWidth: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.iconHaloWidth(haloWidth.toMLNExpression()))
  }

  actual fun setIconHaloBlur(haloBlur: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.iconHaloBlur(haloBlur.toMLNExpression()))
  }

  actual fun setIconTranslate(translate: CompiledExpression<DpOffsetValue>) {
    impl.setProperties(PropertyFactory.iconTranslate(translate.toMLNExpression()))
  }

  actual fun setIconTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    impl.setProperties(PropertyFactory.iconTranslateAnchor(translateAnchor.toMLNExpression()))
  }

  actual fun setTextPitchAlignment(pitchAlignment: CompiledExpression<TextPitchAlignment>) {
    impl.setProperties(PropertyFactory.textPitchAlignment(pitchAlignment.toMLNExpression()))
  }

  actual fun setTextRotationAlignment(
    rotationAlignment: CompiledExpression<TextRotationAlignment>
  ) {
    impl.setProperties(PropertyFactory.textRotationAlignment(rotationAlignment.toMLNExpression()))
  }

  actual fun setTextField(field: CompiledExpression<FormattedValue>) {
    impl.setProperties(PropertyFactory.textField(field.toMLNExpression()))
  }

  actual fun setTextFont(font: CompiledExpression<ListValue<StringValue>>) {
    impl.setProperties(PropertyFactory.textFont(font.toMLNExpression()))
  }

  actual fun setTextSize(size: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.textSize(size.toMLNExpression()))
  }

  actual fun setTextMaxWidth(maxWidth: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.textMaxWidth(maxWidth.toMLNExpression()))
  }

  actual fun setTextLineHeight(lineHeight: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.textLineHeight(lineHeight.toMLNExpression()))
  }

  actual fun setTextLetterSpacing(letterSpacing: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.textLetterSpacing(letterSpacing.toMLNExpression()))
  }

  actual fun setTextJustify(justify: CompiledExpression<TextJustify>) {
    impl.setProperties(PropertyFactory.textJustify(justify.toMLNExpression()))
  }

  actual fun setTextRadialOffset(radialOffset: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.textRadialOffset(radialOffset.toMLNExpression()))
  }

  actual fun setTextVariableAnchor(variableAnchor: CompiledExpression<ListValue<SymbolAnchor>>) {
    impl.setProperties(PropertyFactory.textVariableAnchor(variableAnchor.toMLNExpression()))
  }

  actual fun setTextVariableAnchorOffset(
    variableAnchorOffset: CompiledExpression<TextVariableAnchorOffsetValue>
  ) {
    impl.setProperties(
      PropertyFactory.textVariableAnchorOffset(variableAnchorOffset.toMLNExpression())
    )
  }

  actual fun setTextAnchor(anchor: CompiledExpression<SymbolAnchor>) {
    impl.setProperties(PropertyFactory.textAnchor(anchor.toMLNExpression()))
  }

  actual fun setTextMaxAngle(maxAngle: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.textMaxAngle(maxAngle.toMLNExpression()))
  }

  actual fun setTextWritingMode(writingMode: CompiledExpression<ListValue<TextWritingMode>>) {
    impl.setProperties(PropertyFactory.textWritingMode(writingMode.toMLNExpression()))
  }

  actual fun setTextRotate(rotate: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.textRotate(rotate.toMLNExpression()))
  }

  actual fun setTextPadding(padding: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.textPadding(padding.toMLNExpression()))
  }

  actual fun setTextKeepUpright(keepUpright: CompiledExpression<BooleanValue>) {
    impl.setProperties(PropertyFactory.textKeepUpright(keepUpright.toMLNExpression()))
  }

  actual fun setTextTransform(transform: CompiledExpression<TextTransform>) {
    impl.setProperties(PropertyFactory.textTransform(transform.toMLNExpression()))
  }

  actual fun setTextOffset(offset: CompiledExpression<FloatOffsetValue>) {
    impl.setProperties(PropertyFactory.textOffset(offset.toMLNExpression()))
  }

  actual fun setTextAllowOverlap(allowOverlap: CompiledExpression<BooleanValue>) {
    impl.setProperties(PropertyFactory.textAllowOverlap(allowOverlap.toMLNExpression()))
  }

  actual fun setTextOverlap(overlap: CompiledExpression<SymbolOverlap>) {
    // not implemented by MapLibre-native Android yet
    // impl.setProperties(PropertyFactory.textOverlap(overlap.toMLNExpression()))
  }

  actual fun setTextIgnorePlacement(ignorePlacement: CompiledExpression<BooleanValue>) {
    impl.setProperties(PropertyFactory.textIgnorePlacement(ignorePlacement.toMLNExpression()))
  }

  actual fun setTextOptional(optional: CompiledExpression<BooleanValue>) {
    impl.setProperties(PropertyFactory.textOptional(optional.toMLNExpression()))
  }

  actual fun setTextOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.textOpacity(opacity.toMLNExpression()))
  }

  actual fun setTextColor(color: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.textColor(color.toMLNExpression()))
  }

  actual fun setTextHaloColor(haloColor: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.textHaloColor(haloColor.toMLNExpression()))
  }

  actual fun setTextHaloWidth(haloWidth: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.textHaloWidth(haloWidth.toMLNExpression()))
  }

  actual fun setTextHaloBlur(haloBlur: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.textHaloBlur(haloBlur.toMLNExpression()))
  }

  actual fun setTextTranslate(translate: CompiledExpression<DpOffsetValue>) {
    impl.setProperties(PropertyFactory.textTranslate(translate.toMLNExpression()))
  }

  actual fun setTextTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    impl.setProperties(PropertyFactory.textTranslateAnchor(translateAnchor.toMLNExpression()))
  }
}
