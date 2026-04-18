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

internal actual class SymbolLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val layerId: String = id
  override fun layerType(): String = "symbol"

  actual override var sourceLayer: String
    get() = sourceLayerString()
    set(value) { updateSourceLayer(value) }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    updateFilter(filter)
  }

  // Layout — symbol-*
  actual fun setSymbolPlacement(placement: CompiledExpression<SymbolPlacement>) {
    setLayoutProp("symbol-placement", placement.toJsonString())
  }

  actual fun setSymbolSpacing(spacing: CompiledExpression<DpValue>) {
    setLayoutProp("symbol-spacing", spacing.toJsonString())
  }

  actual fun setSymbolAvoidEdges(avoidEdges: CompiledExpression<BooleanValue>) {
    setLayoutProp("symbol-avoid-edges", avoidEdges.toJsonString())
  }

  actual fun setSymbolSortKey(sortKey: CompiledExpression<FloatValue>) {
    setLayoutProp("symbol-sort-key", sortKey.toJsonString())
  }

  actual fun setSymbolZOrder(zOrder: CompiledExpression<SymbolZOrder>) {
    setLayoutProp("symbol-z-order", zOrder.toJsonString())
  }

  // Layout — icon-*
  actual fun setIconAllowOverlap(allowOverlap: CompiledExpression<BooleanValue>) {
    setLayoutProp("icon-allow-overlap", allowOverlap.toJsonString())
  }

  actual fun setIconOverlap(overlap: CompiledExpression<StringValue>) {
    setLayoutProp("icon-overlap", overlap.toJsonString())
  }

  actual fun setIconIgnorePlacement(ignorePlacement: CompiledExpression<BooleanValue>) {
    setLayoutProp("icon-ignore-placement", ignorePlacement.toJsonString())
  }

  actual fun setIconOptional(optional: CompiledExpression<BooleanValue>) {
    setLayoutProp("icon-optional", optional.toJsonString())
  }

  actual fun setIconRotationAlignment(
    rotationAlignment: CompiledExpression<IconRotationAlignment>
  ) {
    setLayoutProp("icon-rotation-alignment", rotationAlignment.toJsonString())
  }

  actual fun setIconSize(size: CompiledExpression<FloatValue>) {
    setLayoutProp("icon-size", size.toJsonString())
  }

  actual fun setIconTextFit(textFit: CompiledExpression<IconTextFit>) {
    setLayoutProp("icon-text-fit", textFit.toJsonString())
  }

  actual fun setIconTextFitPadding(textFitPadding: CompiledExpression<DpPaddingValue>) {
    setLayoutProp("icon-text-fit-padding", textFitPadding.toJsonString())
  }

  actual fun setIconImage(image: CompiledExpression<ImageValue>) {
    setLayoutProp("icon-image", image.toJsonString())
  }

  actual fun setIconRotate(rotate: CompiledExpression<FloatValue>) {
    setLayoutProp("icon-rotate", rotate.toJsonString())
  }

  actual fun setIconPadding(padding: CompiledExpression<DpPaddingValue>) {
    setLayoutProp("icon-padding", padding.toJsonString())
  }

  actual fun setIconKeepUpright(keepUpright: CompiledExpression<BooleanValue>) {
    setLayoutProp("icon-keep-upright", keepUpright.toJsonString())
  }

  actual fun setIconOffset(offset: CompiledExpression<DpOffsetValue>) {
    setLayoutProp("icon-offset", offset.toJsonString())
  }

  actual fun setIconAnchor(anchor: CompiledExpression<SymbolAnchor>) {
    setLayoutProp("icon-anchor", anchor.toJsonString())
  }

  actual fun setIconPitchAlignment(pitchAlignment: CompiledExpression<IconPitchAlignment>) {
    setLayoutProp("icon-pitch-alignment", pitchAlignment.toJsonString())
  }

  // Paint — icon-*
  actual fun setIconOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProp("icon-opacity", opacity.toJsonString())
  }

  actual fun setIconColor(color: CompiledExpression<ColorValue>) {
    setPaintProp("icon-color", color.toJsonString())
  }

  actual fun setIconHaloColor(haloColor: CompiledExpression<ColorValue>) {
    setPaintProp("icon-halo-color", haloColor.toJsonString())
  }

  actual fun setIconHaloWidth(haloWidth: CompiledExpression<DpValue>) {
    setPaintProp("icon-halo-width", haloWidth.toJsonString())
  }

  actual fun setIconHaloBlur(haloBlur: CompiledExpression<DpValue>) {
    setPaintProp("icon-halo-blur", haloBlur.toJsonString())
  }

  actual fun setIconTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProp("icon-translate", translate.toJsonString())
  }

  actual fun setIconTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    setPaintProp("icon-translate-anchor", translateAnchor.toJsonString())
  }

  // Layout — text-*
  actual fun setTextPitchAlignment(pitchAlignment: CompiledExpression<TextPitchAlignment>) {
    setLayoutProp("text-pitch-alignment", pitchAlignment.toJsonString())
  }

  actual fun setTextRotationAlignment(
    rotationAlignment: CompiledExpression<TextRotationAlignment>
  ) {
    setLayoutProp("text-rotation-alignment", rotationAlignment.toJsonString())
  }

  actual fun setTextField(field: CompiledExpression<FormattedValue>) {
    setLayoutProp("text-field", field.toJsonString())
  }

  actual fun setTextFont(font: CompiledExpression<ListValue<StringValue>>) {
    setLayoutProp("text-font", font.toJsonString())
  }

  actual fun setTextSize(size: CompiledExpression<DpValue>) {
    setLayoutProp("text-size", size.toJsonString())
  }

  actual fun setTextMaxWidth(maxWidth: CompiledExpression<FloatValue>) {
    setLayoutProp("text-max-width", maxWidth.toJsonString())
  }

  actual fun setTextLineHeight(lineHeight: CompiledExpression<FloatValue>) {
    setLayoutProp("text-line-height", lineHeight.toJsonString())
  }

  actual fun setTextLetterSpacing(letterSpacing: CompiledExpression<FloatValue>) {
    setLayoutProp("text-letter-spacing", letterSpacing.toJsonString())
  }

  actual fun setTextJustify(justify: CompiledExpression<TextJustify>) {
    setLayoutProp("text-justify", justify.toJsonString())
  }

  actual fun setTextRadialOffset(radialOffset: CompiledExpression<FloatValue>) {
    setLayoutProp("text-radial-offset", radialOffset.toJsonString())
  }

  actual fun setTextVariableAnchor(variableAnchor: CompiledExpression<ListValue<SymbolAnchor>>) {
    setLayoutProp("text-variable-anchor", variableAnchor.toJsonString())
  }

  actual fun setTextVariableAnchorOffset(
    variableAnchorOffset: CompiledExpression<TextVariableAnchorOffsetValue>
  ) {
    setLayoutProp("text-variable-anchor-offset", variableAnchorOffset.toJsonString())
  }

  actual fun setTextAnchor(anchor: CompiledExpression<SymbolAnchor>) {
    setLayoutProp("text-anchor", anchor.toJsonString())
  }

  actual fun setTextMaxAngle(maxAngle: CompiledExpression<FloatValue>) {
    setLayoutProp("text-max-angle", maxAngle.toJsonString())
  }

  actual fun setTextWritingMode(writingMode: CompiledExpression<ListValue<TextWritingMode>>) {
    setLayoutProp("text-writing-mode", writingMode.toJsonString())
  }

  actual fun setTextRotate(rotate: CompiledExpression<FloatValue>) {
    setLayoutProp("text-rotate", rotate.toJsonString())
  }

  actual fun setTextPadding(padding: CompiledExpression<DpValue>) {
    setLayoutProp("text-padding", padding.toJsonString())
  }

  actual fun setTextKeepUpright(keepUpright: CompiledExpression<BooleanValue>) {
    setLayoutProp("text-keep-upright", keepUpright.toJsonString())
  }

  actual fun setTextTransform(transform: CompiledExpression<TextTransform>) {
    setLayoutProp("text-transform", transform.toJsonString())
  }

  actual fun setTextOffset(offset: CompiledExpression<FloatOffsetValue>) {
    setLayoutProp("text-offset", offset.toJsonString())
  }

  actual fun setTextAllowOverlap(allowOverlap: CompiledExpression<BooleanValue>) {
    setLayoutProp("text-allow-overlap", allowOverlap.toJsonString())
  }

  actual fun setTextOverlap(overlap: CompiledExpression<SymbolOverlap>) {
    setLayoutProp("text-overlap", overlap.toJsonString())
  }

  actual fun setTextIgnorePlacement(ignorePlacement: CompiledExpression<BooleanValue>) {
    setLayoutProp("text-ignore-placement", ignorePlacement.toJsonString())
  }

  actual fun setTextOptional(optional: CompiledExpression<BooleanValue>) {
    setLayoutProp("text-optional", optional.toJsonString())
  }

  // Paint — text-*
  actual fun setTextOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProp("text-opacity", opacity.toJsonString())
  }

  actual fun setTextColor(color: CompiledExpression<ColorValue>) {
    setPaintProp("text-color", color.toJsonString())
  }

  actual fun setTextHaloColor(haloColor: CompiledExpression<ColorValue>) {
    setPaintProp("text-halo-color", haloColor.toJsonString())
  }

  actual fun setTextHaloWidth(haloWidth: CompiledExpression<DpValue>) {
    setPaintProp("text-halo-width", haloWidth.toJsonString())
  }

  actual fun setTextHaloBlur(haloBlur: CompiledExpression<DpValue>) {
    setPaintProp("text-halo-blur", haloBlur.toJsonString())
  }

  actual fun setTextTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProp("text-translate", translate.toJsonString())
  }

  actual fun setTextTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    setPaintProp("text-translate-anchor", translateAnchor.toJsonString())
  }
}
