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
import org.maplibre.compose.util.toJsonString
import org.maplibre.kmp.native.style.layers.SymbolLayer as MLNSymbolLayer

internal actual class SymbolLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  override val impl = MLNSymbolLayer(id, source.id)

  actual override var sourceLayer: String
    get() = impl.sourceLayer
    set(value) {
      impl.sourceLayer = value
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    filter.toJsonString()?.let { impl.setFilter(it) }
  }

  actual fun setSymbolPlacement(placement: CompiledExpression<SymbolPlacement>) {
    placement.toJsonString()?.let { impl.setProperty("symbol-placement", it) }
  }

  actual fun setSymbolSpacing(spacing: CompiledExpression<DpValue>) {
    spacing.toJsonString()?.let { impl.setProperty("symbol-spacing", it) }
  }

  actual fun setSymbolAvoidEdges(avoidEdges: CompiledExpression<BooleanValue>) {
    avoidEdges.toJsonString()?.let { impl.setProperty("symbol-avoid-edges", it) }
  }

  actual fun setSymbolSortKey(sortKey: CompiledExpression<FloatValue>) {
    sortKey.toJsonString()?.let { impl.setProperty("symbol-sort-key", it) }
  }

  actual fun setSymbolZOrder(zOrder: CompiledExpression<SymbolZOrder>) {
    zOrder.toJsonString()?.let { impl.setProperty("symbol-z-order", it) }
  }

  actual fun setIconAllowOverlap(allowOverlap: CompiledExpression<BooleanValue>) {
    allowOverlap.toJsonString()?.let { impl.setProperty("icon-allow-overlap", it) }
  }

  actual fun setIconOverlap(overlap: CompiledExpression<StringValue>) {
    overlap.toJsonString()?.let { impl.setProperty("icon-overlap", it) }
  }

  actual fun setIconIgnorePlacement(ignorePlacement: CompiledExpression<BooleanValue>) {
    ignorePlacement.toJsonString()?.let { impl.setProperty("icon-ignore-placement", it) }
  }

  actual fun setIconOptional(optional: CompiledExpression<BooleanValue>) {
    optional.toJsonString()?.let { impl.setProperty("icon-optional", it) }
  }

  actual fun setIconRotationAlignment(
    rotationAlignment: CompiledExpression<IconRotationAlignment>
  ) {
    rotationAlignment.toJsonString()?.let { impl.setProperty("icon-rotation-alignment", it) }
  }

  actual fun setIconSize(size: CompiledExpression<FloatValue>) {
    size.toJsonString()?.let { impl.setProperty("icon-size", it) }
  }

  actual fun setIconTextFit(textFit: CompiledExpression<IconTextFit>) {
    textFit.toJsonString()?.let { impl.setProperty("icon-text-fit", it) }
  }

  actual fun setIconTextFitPadding(textFitPadding: CompiledExpression<DpPaddingValue>) {
    textFitPadding.toJsonString()?.let { impl.setProperty("icon-text-fit-padding", it) }
  }

  actual fun setIconImage(image: CompiledExpression<ImageValue>) {
    image.toJsonString()?.let { impl.setProperty("icon-image", it) }
  }

  actual fun setIconRotate(rotate: CompiledExpression<FloatValue>) {
    rotate.toJsonString()?.let { impl.setProperty("icon-rotate", it) }
  }

  actual fun setIconPadding(padding: CompiledExpression<DpPaddingValue>) {
    padding.toJsonString()?.let { impl.setProperty("icon-padding", it) }
  }

  actual fun setIconKeepUpright(keepUpright: CompiledExpression<BooleanValue>) {
    keepUpright.toJsonString()?.let { impl.setProperty("icon-keep-upright", it) }
  }

  actual fun setIconOffset(offset: CompiledExpression<DpOffsetValue>) {
    offset.toJsonString()?.let { impl.setProperty("icon-offset", it) }
  }

  actual fun setIconAnchor(anchor: CompiledExpression<SymbolAnchor>) {
    anchor.toJsonString()?.let { impl.setProperty("icon-anchor", it) }
  }

  actual fun setIconPitchAlignment(pitchAlignment: CompiledExpression<IconPitchAlignment>) {
    pitchAlignment.toJsonString()?.let { impl.setProperty("icon-pitch-alignment", it) }
  }

  actual fun setIconOpacity(opacity: CompiledExpression<FloatValue>) {
    opacity.toJsonString()?.let { impl.setProperty("icon-opacity", it) }
  }

  actual fun setIconColor(color: CompiledExpression<ColorValue>) {
    color.toJsonString()?.let { impl.setProperty("icon-color", it) }
  }

  actual fun setIconHaloColor(haloColor: CompiledExpression<ColorValue>) {
    haloColor.toJsonString()?.let { impl.setProperty("icon-halo-color", it) }
  }

  actual fun setIconHaloWidth(haloWidth: CompiledExpression<DpValue>) {
    haloWidth.toJsonString()?.let { impl.setProperty("icon-halo-width", it) }
  }

  actual fun setIconHaloBlur(haloBlur: CompiledExpression<DpValue>) {
    haloBlur.toJsonString()?.let { impl.setProperty("icon-halo-blur", it) }
  }

  actual fun setIconTranslate(translate: CompiledExpression<DpOffsetValue>) {
    translate.toJsonString()?.let { impl.setProperty("icon-translate", it) }
  }

  actual fun setIconTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    translateAnchor.toJsonString()?.let { impl.setProperty("icon-translate-anchor", it) }
  }

  actual fun setTextPitchAlignment(pitchAlignment: CompiledExpression<TextPitchAlignment>) {
    pitchAlignment.toJsonString()?.let { impl.setProperty("text-pitch-alignment", it) }
  }

  actual fun setTextRotationAlignment(
    rotationAlignment: CompiledExpression<TextRotationAlignment>
  ) {
    rotationAlignment.toJsonString()?.let { impl.setProperty("text-rotation-alignment", it) }
  }

  actual fun setTextField(field: CompiledExpression<FormattedValue>) {
    field.toJsonString()?.let { impl.setProperty("text-field", it) }
  }

  actual fun setTextFont(font: CompiledExpression<ListValue<StringValue>>) {
    font.toJsonString()?.let { impl.setProperty("text-font", it) }
  }

  actual fun setTextSize(size: CompiledExpression<DpValue>) {
    size.toJsonString()?.let { impl.setProperty("text-size", it) }
  }

  actual fun setTextMaxWidth(maxWidth: CompiledExpression<FloatValue>) {
    maxWidth.toJsonString()?.let { impl.setProperty("text-max-width", it) }
  }

  actual fun setTextLineHeight(lineHeight: CompiledExpression<FloatValue>) {
    lineHeight.toJsonString()?.let { impl.setProperty("text-line-height", it) }
  }

  actual fun setTextLetterSpacing(letterSpacing: CompiledExpression<FloatValue>) {
    letterSpacing.toJsonString()?.let { impl.setProperty("text-letter-spacing", it) }
  }

  actual fun setTextJustify(justify: CompiledExpression<TextJustify>) {
    justify.toJsonString()?.let { impl.setProperty("text-justify", it) }
  }

  actual fun setTextRadialOffset(radialOffset: CompiledExpression<FloatValue>) {
    radialOffset.toJsonString()?.let { impl.setProperty("text-radial-offset", it) }
  }

  actual fun setTextVariableAnchor(variableAnchor: CompiledExpression<ListValue<SymbolAnchor>>) {
    variableAnchor.toJsonString()?.let { impl.setProperty("text-variable-anchor", it) }
  }

  actual fun setTextVariableAnchorOffset(
    variableAnchorOffset: CompiledExpression<TextVariableAnchorOffsetValue>
  ) {
    variableAnchorOffset.toJsonString()?.let { impl.setProperty("text-variable-anchor-offset", it) }
  }

  actual fun setTextAnchor(anchor: CompiledExpression<SymbolAnchor>) {
    anchor.toJsonString()?.let { impl.setProperty("text-anchor", it) }
  }

  actual fun setTextMaxAngle(maxAngle: CompiledExpression<FloatValue>) {
    maxAngle.toJsonString()?.let { impl.setProperty("text-max-angle", it) }
  }

  actual fun setTextWritingMode(writingMode: CompiledExpression<ListValue<TextWritingMode>>) {
    writingMode.toJsonString()?.let { impl.setProperty("text-writing-mode", it) }
  }

  actual fun setTextRotate(rotate: CompiledExpression<FloatValue>) {
    rotate.toJsonString()?.let { impl.setProperty("text-rotate", it) }
  }

  actual fun setTextPadding(padding: CompiledExpression<DpValue>) {
    padding.toJsonString()?.let { impl.setProperty("text-padding", it) }
  }

  actual fun setTextKeepUpright(keepUpright: CompiledExpression<BooleanValue>) {
    keepUpright.toJsonString()?.let { impl.setProperty("text-keep-upright", it) }
  }

  actual fun setTextTransform(transform: CompiledExpression<TextTransform>) {
    transform.toJsonString()?.let { impl.setProperty("text-transform", it) }
  }

  actual fun setTextOffset(offset: CompiledExpression<FloatOffsetValue>) {
    offset.toJsonString()?.let { impl.setProperty("text-offset", it) }
  }

  actual fun setTextAllowOverlap(allowOverlap: CompiledExpression<BooleanValue>) {
    allowOverlap.toJsonString()?.let { impl.setProperty("text-allow-overlap", it) }
  }

  actual fun setTextOverlap(overlap: CompiledExpression<SymbolOverlap>) {
    overlap.toJsonString()?.let { impl.setProperty("text-overlap", it) }
  }

  actual fun setTextIgnorePlacement(ignorePlacement: CompiledExpression<BooleanValue>) {
    ignorePlacement.toJsonString()?.let { impl.setProperty("text-ignore-placement", it) }
  }

  actual fun setTextOptional(optional: CompiledExpression<BooleanValue>) {
    optional.toJsonString()?.let { impl.setProperty("text-optional", it) }
  }

  actual fun setTextOpacity(opacity: CompiledExpression<FloatValue>) {
    opacity.toJsonString()?.let { impl.setProperty("text-opacity", it) }
  }

  actual fun setTextColor(color: CompiledExpression<ColorValue>) {
    color.toJsonString()?.let { impl.setProperty("text-color", it) }
  }

  actual fun setTextHaloColor(haloColor: CompiledExpression<ColorValue>) {
    haloColor.toJsonString()?.let { impl.setProperty("text-halo-color", it) }
  }

  actual fun setTextHaloWidth(haloWidth: CompiledExpression<DpValue>) {
    haloWidth.toJsonString()?.let { impl.setProperty("text-halo-width", it) }
  }

  actual fun setTextHaloBlur(haloBlur: CompiledExpression<DpValue>) {
    haloBlur.toJsonString()?.let { impl.setProperty("text-halo-blur", it) }
  }

  actual fun setTextTranslate(translate: CompiledExpression<DpOffsetValue>) {
    translate.toJsonString()?.let { impl.setProperty("text-translate", it) }
  }

  actual fun setTextTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    translateAnchor.toJsonString()?.let { impl.setProperty("text-translate-anchor", it) }
  }
}
