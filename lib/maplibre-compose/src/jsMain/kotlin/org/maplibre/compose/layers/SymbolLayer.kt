package org.maplibre.compose.layers

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

internal actual class SymbolLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  override val impl = TODO()

  actual override var sourceLayer: String = TODO()

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    TODO()
  }

  actual fun setSymbolPlacement(placement: CompiledExpression<SymbolPlacement>) {
    TODO()
  }

  actual fun setSymbolSpacing(spacing: CompiledExpression<DpValue>) {
    TODO()
  }

  actual fun setSymbolAvoidEdges(avoidEdges: CompiledExpression<BooleanValue>) {
    TODO()
  }

  actual fun setSymbolSortKey(sortKey: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setSymbolZOrder(zOrder: CompiledExpression<SymbolZOrder>) {
    TODO()
  }

  actual fun setIconAllowOverlap(allowOverlap: CompiledExpression<BooleanValue>) {
    TODO()
  }

  actual fun setIconOverlap(overlap: CompiledExpression<StringValue>) {
    TODO()
  }

  actual fun setIconIgnorePlacement(ignorePlacement: CompiledExpression<BooleanValue>) {
    TODO()
  }

  actual fun setIconOptional(optional: CompiledExpression<BooleanValue>) {
    TODO()
  }

  actual fun setIconRotationAlignment(
    rotationAlignment: CompiledExpression<IconRotationAlignment>
  ) {
    TODO()
  }

  actual fun setIconSize(size: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setIconTextFit(textFit: CompiledExpression<IconTextFit>) {
    TODO()
  }

  actual fun setIconTextFitPadding(textFitPadding: CompiledExpression<DpPaddingValue>) {
    TODO()
  }

  actual fun setIconImage(image: CompiledExpression<ImageValue>) {
    TODO()
  }

  actual fun setIconRotate(rotate: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setIconPadding(padding: CompiledExpression<DpPaddingValue>) {
    TODO()
  }

  actual fun setIconKeepUpright(keepUpright: CompiledExpression<BooleanValue>) {
    TODO()
  }

  actual fun setIconOffset(offset: CompiledExpression<DpOffsetValue>) {
    TODO()
  }

  actual fun setIconAnchor(anchor: CompiledExpression<SymbolAnchor>) {
    TODO()
  }

  actual fun setIconPitchAlignment(pitchAlignment: CompiledExpression<IconPitchAlignment>) {
    TODO()
  }

  actual fun setIconOpacity(opacity: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setIconColor(color: CompiledExpression<ColorValue>) {
    TODO()
  }

  actual fun setIconHaloColor(haloColor: CompiledExpression<ColorValue>) {
    TODO()
  }

  actual fun setIconHaloWidth(haloWidth: CompiledExpression<DpValue>) {
    TODO()
  }

  actual fun setIconHaloBlur(haloBlur: CompiledExpression<DpValue>) {
    TODO()
  }

  actual fun setIconTranslate(translate: CompiledExpression<DpOffsetValue>) {
    TODO()
  }

  actual fun setIconTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    TODO()
  }

  actual fun setTextPitchAlignment(pitchAlignment: CompiledExpression<TextPitchAlignment>) {
    TODO()
  }

  actual fun setTextRotationAlignment(
    rotationAlignment: CompiledExpression<TextRotationAlignment>
  ) {
    TODO()
  }

  actual fun setTextField(field: CompiledExpression<FormattedValue>) {
    TODO()
  }

  actual fun setTextFont(font: CompiledExpression<ListValue<StringValue>>) {
    TODO()
  }

  actual fun setTextSize(size: CompiledExpression<DpValue>) {
    TODO()
  }

  actual fun setTextMaxWidth(maxWidth: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setTextLineHeight(lineHeight: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setTextLetterSpacing(letterSpacing: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setTextJustify(justify: CompiledExpression<TextJustify>) {
    TODO()
  }

  actual fun setTextRadialOffset(radialOffset: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setTextVariableAnchor(variableAnchor: CompiledExpression<ListValue<SymbolAnchor>>) {
    TODO()
  }

  actual fun setTextVariableAnchorOffset(
    variableAnchorOffset: CompiledExpression<TextVariableAnchorOffsetValue>
  ) {
    TODO()
  }

  actual fun setTextAnchor(anchor: CompiledExpression<SymbolAnchor>) {
    TODO()
  }

  actual fun setTextMaxAngle(maxAngle: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setTextWritingMode(writingMode: CompiledExpression<ListValue<TextWritingMode>>) {
    TODO()
  }

  actual fun setTextRotate(rotate: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setTextPadding(padding: CompiledExpression<DpValue>) {
    TODO()
  }

  actual fun setTextKeepUpright(keepUpright: CompiledExpression<BooleanValue>) {
    TODO()
  }

  actual fun setTextTransform(transform: CompiledExpression<TextTransform>) {
    TODO()
  }

  actual fun setTextOffset(offset: CompiledExpression<FloatOffsetValue>) {
    TODO()
  }

  actual fun setTextAllowOverlap(allowOverlap: CompiledExpression<BooleanValue>) {
    TODO()
  }

  actual fun setTextOverlap(overlap: CompiledExpression<SymbolOverlap>) {
    TODO()
  }

  actual fun setTextIgnorePlacement(ignorePlacement: CompiledExpression<BooleanValue>) {
    TODO()
  }

  actual fun setTextOptional(optional: CompiledExpression<BooleanValue>) {
    TODO()
  }

  actual fun setTextOpacity(opacity: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setTextColor(color: CompiledExpression<ColorValue>) {
    TODO()
  }

  actual fun setTextHaloColor(haloColor: CompiledExpression<ColorValue>) {
    TODO()
  }

  actual fun setTextHaloWidth(haloWidth: CompiledExpression<DpValue>) {
    TODO()
  }

  actual fun setTextHaloBlur(haloBlur: CompiledExpression<DpValue>) {
    TODO()
  }

  actual fun setTextTranslate(translate: CompiledExpression<DpOffsetValue>) {
    TODO()
  }

  actual fun setTextTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    TODO()
  }
}
