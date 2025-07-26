package org.maplibre.compose.layers

import org.maplibre.android.style.expressions.Expression as MLNExpression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer as MLNSymbolLayer
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.DpPaddingValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatOffsetValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.FormattedValue
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.IconTextFit
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.ListValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.expressions.value.SymbolOverlap
import org.maplibre.compose.expressions.value.SymbolPlacement
import org.maplibre.compose.expressions.value.SymbolZOrder
import org.maplibre.compose.expressions.value.TextJustify
import org.maplibre.compose.expressions.value.TextPitchAlignment
import org.maplibre.compose.expressions.value.TextRotationAlignment
import org.maplibre.compose.expressions.value.TextTransform
import org.maplibre.compose.expressions.value.TextVariableAnchorOffsetValue
import org.maplibre.compose.expressions.value.TextWritingMode
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toMLNExpression

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
