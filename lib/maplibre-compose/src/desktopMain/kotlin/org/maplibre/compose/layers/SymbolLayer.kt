package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonPrimitive
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
import org.maplibre.compose.util.toFfiJsonValue

internal actual class SymbolLayer actual constructor(id: String, source: Source) :
  FeatureLayer(id, source) {

  override val type: String = "symbol"

  override val sourceId: String = source.id

  // TODO(maplibre-native-ffi): the FFI has no source-layer setter, and `setLayerProperty` only
  //   reaches layout and paint properties, so a change made after the layer is attached is
  //   dropped. Attaching picks the value up because it goes out with the layer JSON.
  actual override var sourceLayer: String = ""
    set(value) {
      field = value
      mutate { map ->
        map.setLayerProperty(id, "source-layer", JsonPrimitive(value).toFfiJsonValue())
      }
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    setFilterExpression(filter)
  }

  actual fun setSymbolPlacement(placement: CompiledExpression<SymbolPlacement>) {
    setLayoutProperty("symbol-placement", placement)
  }

  actual fun setSymbolSpacing(spacing: CompiledExpression<DpValue>) {
    setLayoutProperty("symbol-spacing", spacing)
  }

  actual fun setSymbolAvoidEdges(avoidEdges: CompiledExpression<BooleanValue>) {
    setLayoutProperty("symbol-avoid-edges", avoidEdges)
  }

  actual fun setSymbolSortKey(sortKey: CompiledExpression<FloatValue>) {
    setLayoutProperty("symbol-sort-key", sortKey)
  }

  actual fun setSymbolZOrder(zOrder: CompiledExpression<SymbolZOrder>) {
    setLayoutProperty("symbol-z-order", zOrder)
  }

  actual fun setIconAllowOverlap(allowOverlap: CompiledExpression<BooleanValue>) {
    setLayoutProperty("icon-allow-overlap", allowOverlap)
  }

  // Unlike Android and iOS, whose SDKs never exposed a setter for it, the style JSON path can carry
  // `icon-overlap` straight through to the core, which has supported it since it superseded
  // `icon-allow-overlap`.
  actual fun setIconOverlap(overlap: CompiledExpression<StringValue>) {
    setLayoutProperty("icon-overlap", overlap)
  }

  actual fun setIconIgnorePlacement(ignorePlacement: CompiledExpression<BooleanValue>) {
    setLayoutProperty("icon-ignore-placement", ignorePlacement)
  }

  actual fun setIconOptional(optional: CompiledExpression<BooleanValue>) {
    setLayoutProperty("icon-optional", optional)
  }

  actual fun setIconRotationAlignment(
    rotationAlignment: CompiledExpression<IconRotationAlignment>
  ) {
    setLayoutProperty("icon-rotation-alignment", rotationAlignment)
  }

  actual fun setIconSize(size: CompiledExpression<FloatValue>) {
    setLayoutProperty("icon-size", size)
  }

  actual fun setIconTextFit(textFit: CompiledExpression<IconTextFit>) {
    setLayoutProperty("icon-text-fit", textFit)
  }

  actual fun setIconTextFitPadding(textFitPadding: CompiledExpression<DpPaddingValue>) {
    setLayoutProperty("icon-text-fit-padding", textFitPadding)
  }

  actual fun setIconImage(image: CompiledExpression<ImageValue>) {
    setLayoutProperty("icon-image", image)
  }

  actual fun setIconRotate(rotate: CompiledExpression<FloatValue>) {
    setLayoutProperty("icon-rotate", rotate)
  }

  actual fun setIconPadding(padding: CompiledExpression<DpPaddingValue>) {
    setLayoutProperty("icon-padding", padding)
  }

  actual fun setIconKeepUpright(keepUpright: CompiledExpression<BooleanValue>) {
    setLayoutProperty("icon-keep-upright", keepUpright)
  }

  actual fun setIconOffset(offset: CompiledExpression<DpOffsetValue>) {
    setLayoutProperty("icon-offset", offset)
  }

  actual fun setIconAnchor(anchor: CompiledExpression<SymbolAnchor>) {
    setLayoutProperty("icon-anchor", anchor)
  }

  actual fun setIconPitchAlignment(pitchAlignment: CompiledExpression<IconPitchAlignment>) {
    setLayoutProperty("icon-pitch-alignment", pitchAlignment)
  }

  actual fun setIconOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("icon-opacity", opacity)
  }

  actual fun setIconColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("icon-color", color)
  }

  actual fun setIconHaloColor(haloColor: CompiledExpression<ColorValue>) {
    setPaintProperty("icon-halo-color", haloColor)
  }

  actual fun setIconHaloWidth(haloWidth: CompiledExpression<DpValue>) {
    setPaintProperty("icon-halo-width", haloWidth)
  }

  actual fun setIconHaloBlur(haloBlur: CompiledExpression<DpValue>) {
    setPaintProperty("icon-halo-blur", haloBlur)
  }

  actual fun setIconTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProperty("icon-translate", translate)
  }

  actual fun setIconTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    setPaintProperty("icon-translate-anchor", translateAnchor)
  }

  actual fun setTextPitchAlignment(pitchAlignment: CompiledExpression<TextPitchAlignment>) {
    setLayoutProperty("text-pitch-alignment", pitchAlignment)
  }

  actual fun setTextRotationAlignment(
    rotationAlignment: CompiledExpression<TextRotationAlignment>
  ) {
    setLayoutProperty("text-rotation-alignment", rotationAlignment)
  }

  actual fun setTextField(field: CompiledExpression<FormattedValue>) {
    setLayoutProperty("text-field", field)
  }

  actual fun setTextFont(font: CompiledExpression<ListValue<StringValue>>) {
    setLayoutProperty("text-font", font)
  }

  actual fun setTextSize(size: CompiledExpression<DpValue>) {
    setLayoutProperty("text-size", size)
  }

  actual fun setTextMaxWidth(maxWidth: CompiledExpression<FloatValue>) {
    setLayoutProperty("text-max-width", maxWidth)
  }

  actual fun setTextLineHeight(lineHeight: CompiledExpression<FloatValue>) {
    setLayoutProperty("text-line-height", lineHeight)
  }

  actual fun setTextLetterSpacing(letterSpacing: CompiledExpression<FloatValue>) {
    setLayoutProperty("text-letter-spacing", letterSpacing)
  }

  actual fun setTextJustify(justify: CompiledExpression<TextJustify>) {
    setLayoutProperty("text-justify", justify)
  }

  actual fun setTextRadialOffset(radialOffset: CompiledExpression<FloatValue>) {
    setLayoutProperty("text-radial-offset", radialOffset)
  }

  actual fun setTextVariableAnchor(variableAnchor: CompiledExpression<ListValue<SymbolAnchor>>) {
    setLayoutProperty("text-variable-anchor", variableAnchor)
  }

  actual fun setTextVariableAnchorOffset(
    variableAnchorOffset: CompiledExpression<TextVariableAnchorOffsetValue>
  ) {
    setLayoutProperty("text-variable-anchor-offset", variableAnchorOffset)
  }

  actual fun setTextAnchor(anchor: CompiledExpression<SymbolAnchor>) {
    setLayoutProperty("text-anchor", anchor)
  }

  actual fun setTextMaxAngle(maxAngle: CompiledExpression<FloatValue>) {
    setLayoutProperty("text-max-angle", maxAngle)
  }

  actual fun setTextWritingMode(writingMode: CompiledExpression<ListValue<TextWritingMode>>) {
    setLayoutProperty("text-writing-mode", writingMode)
  }

  actual fun setTextRotate(rotate: CompiledExpression<FloatValue>) {
    setLayoutProperty("text-rotate", rotate)
  }

  actual fun setTextPadding(padding: CompiledExpression<DpValue>) {
    setLayoutProperty("text-padding", padding)
  }

  actual fun setTextKeepUpright(keepUpright: CompiledExpression<BooleanValue>) {
    setLayoutProperty("text-keep-upright", keepUpright)
  }

  actual fun setTextTransform(transform: CompiledExpression<TextTransform>) {
    setLayoutProperty("text-transform", transform)
  }

  actual fun setTextOffset(offset: CompiledExpression<FloatOffsetValue>) {
    setLayoutProperty("text-offset", offset)
  }

  actual fun setTextAllowOverlap(allowOverlap: CompiledExpression<BooleanValue>) {
    setLayoutProperty("text-allow-overlap", allowOverlap)
  }

  // See [setIconOverlap]: available here even though the mobile SDKs never bound it.
  actual fun setTextOverlap(overlap: CompiledExpression<SymbolOverlap>) {
    setLayoutProperty("text-overlap", overlap)
  }

  actual fun setTextIgnorePlacement(ignorePlacement: CompiledExpression<BooleanValue>) {
    setLayoutProperty("text-ignore-placement", ignorePlacement)
  }

  actual fun setTextOptional(optional: CompiledExpression<BooleanValue>) {
    setLayoutProperty("text-optional", optional)
  }

  actual fun setTextOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("text-opacity", opacity)
  }

  actual fun setTextColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("text-color", color)
  }

  actual fun setTextHaloColor(haloColor: CompiledExpression<ColorValue>) {
    setPaintProperty("text-halo-color", haloColor)
  }

  actual fun setTextHaloWidth(haloWidth: CompiledExpression<DpValue>) {
    setPaintProperty("text-halo-width", haloWidth)
  }

  actual fun setTextHaloBlur(haloBlur: CompiledExpression<DpValue>) {
    setPaintProperty("text-halo-blur", haloBlur)
  }

  actual fun setTextTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProperty("text-translate", translate)
  }

  actual fun setTextTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    setPaintProperty("text-translate-anchor", translateAnchor)
  }
}
