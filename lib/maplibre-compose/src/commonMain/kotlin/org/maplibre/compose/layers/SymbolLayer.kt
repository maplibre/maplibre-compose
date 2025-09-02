package org.maplibre.compose.layers

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.div
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.dsl.offset
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
import org.maplibre.compose.expressions.value.TextUnitOffsetValue
import org.maplibre.compose.expressions.value.TextUnitValue
import org.maplibre.compose.expressions.value.TextVariableAnchorOffsetValue
import org.maplibre.compose.expressions.value.TextWritingMode
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceReferenceEffect
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MaplibreComposable

private const val ASSUMED_SP = 16f // MapLibre's default text size

@Composable
private fun rememberDpCompiler(dpPerSp: Dp) =
  rememberPropertyCompiler(
    emScale = const(ASSUMED_SP * dpPerSp.value),
    spScale = const(dpPerSp.value),
  )

@Composable
private fun rememberEmCompiler(textSize: Expression<TextUnitValue>): LayerPropertyCompiler {
  val compileWithSpTextSize =
    rememberPropertyCompiler(emScale = const(ASSUMED_SP), spScale = const(1f))
  val textSizeSp = compileWithSpTextSize(textSize)
  val spScale = remember(textSizeSp) { const(1f) / textSizeSp.cast() }
  return rememberPropertyCompiler(emScale = const(1f), spScale = spScale)
}

/**
 * A symbol layer draws data from the [sourceLayer] in the given [source] as icons and/or text
 * labels.
 *
 * @param id Unique layer name.
 * @param source Vector data source for this layer.
 * @param sourceLayer Layer to use from the given vector tile [source].
 * @param minZoom The minimum zoom level for the layer. At zoom levels less than this, the layer
 *   will be hidden. A value in the range of `[0..24]`.
 * @param maxZoom The maximum zoom level for the layer. At zoom levels equal to or greater than
 *   this, the layer will be hidden. A value in the range of `[0..24]`.
 * @param filter An expression specifying conditions on source features. Only features that match
 *   the filter are displayed. Zoom expressions in filters are only evaluated at integer zoom
 *   levels. The [featureState][org.maplibre.compose.expressions.dsl.Feature.state] expression is
 *   not supported in filter expressions.
 * @param visible Whether the layer should be displayed.
 * @param sortKey Sorts features within this layer in ascending order based on this value. Features
 *   with a higher sort key will appear above features with a lower sort key.
 * @param placement Symbol placement relative to its geometry.
 * @param spacing Distance between two symbol anchors.
 *
 *   Only applicable when [placement] is [SymbolPlacement.Line].
 *
 * @param avoidEdges If true, the symbols will not cross tile edges to avoid mutual collisions.
 *   Recommended in layers that don't have enough padding in the vector tile to prevent collisions,
 *   or if it is a point symbol layer placed after a line symbol layer.
 * @param zOrder Determines whether overlapping symbols in the same layer are rendered in the order
 *   that they appear in the data source or by their y-position relative to the viewport. To control
 *   the order and prioritization of symbols otherwise, use [sortKey].
 * @param iconImage Image to use for drawing an image background.
 * @param iconOpacity The opacity at which the icon will be drawn. A value in the range `[0..1]`.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconColor The color of the icon. This can only be used with SDF icons.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconHaloColor The color of the icon's halo. Icon halos can only be used with SDF icons.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconHaloWidth Distance of halo to the icon outline. The unit is in dp only for SDF sprites
 *   that were created with a blur radius of 8, multiplied by the display density. I.e., the radius
 *   needs to be 16 for @2x sprites, etc.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconHaloBlur Fade out the halo towards the outside.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconSize Scales the original size of the icon by the provided factor. The new pixel size
 *   of the image will be the original pixel size multiplied by [iconSize]. 1 is the original size;
 *   3 triples the size of the image.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconRotationAlignment In combination with [placement], determines the rotation behavior of
 *   icons.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconPitchAlignment Orientation of icon when map is pitched.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconTextFit Scales the icon to fit around the associated text.
 *
 *   Ignored if not both [iconImage] and [textField] are specified.
 *
 * @param iconTextFitPadding Size of the additional area added to dimensions determined by
 *   [iconTextFit].
 *
 *   Only applicable when if [iconTextFit] is not [IconTextFit.None]. Ignored if not both
 *   [iconImage] and [textField] are specified.
 *
 * @param iconKeepUpright If true, the icon may be flipped to prevent it from being rendered
 *   upside-down.
 *
 *   Only applicable if [iconRotationAlignment] is [IconRotationAlignment.Map] and [placement] is
 *   either [SymbolPlacement.Line] or [SymbolPlacement.LineCenter].
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconRotate Rotates the icon clockwise by the number in degrees.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconAnchor Part of the icon placed closest to the anchor.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconOffset Offset distance of icon from its anchor. Positive values indicate right and
 *   down, while negative values indicate left and up. Each component is multiplied by the value of
 *   [iconSize] to obtain the final offset in dp. When combined with [iconRotate] the offset will be
 *   as if the rotated direction was up.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconPadding Size of additional area round the icon bounding box used for detecting symbol
 *   collisions.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconAllowOverlap If true, the icon will be visible even if it collides with other
 *   previously drawn symbols.
 *
 *   Ignored if [iconImage] is not specified, overridden by [iconOverlap], if specified.
 *
 * @param iconOverlap Controls whether to show an icon when it overlaps other symbols on the map.
 *
 *   Ignored if [iconImage] is not specified.
 *
 *   **Note**: This property is not supported on native platforms yet, see
 *   [maplibre-native#251](https://github.com/maplibre/maplibre-native/issues/251)**
 *
 * @param iconIgnorePlacement If true, other symbols can be visible even if they collide with the
 *   icon.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconOptional If true, text will display without their corresponding icons when the icon
 *   collides with other symbols and the text does not.
 *
 *   Ignored if not both [iconImage] and [textField] are specified.
 *
 * @param iconTranslate The geometry's offset relative to the [iconTranslateAnchor]. Negative
 *   numbers indicate left and up, respectively.
 *
 *   Ignored if [iconImage] is not specified.
 *
 * @param iconTranslateAnchor Frame of reference for offsetting geometry.
 *
 *   Ignored if [iconTranslate] is not set.
 *
 * @param textField Value to use for a text label. Use e.g. `format(const("My label"))` to display
 *   the plain string "My label".
 *
 *   The text can also be formatted, employing different colors, fonts, etc., see
 *   [format][org.maplibre.compose.expressions.dsl.format].
 *
 * @param textOpacity The opacity at which the text will be drawn.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textColor The color with which the text will be drawn.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textHaloColor The color of the text's halo, which helps it stand out from backgrounds.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textHaloWidth Distance of halo to the font outline. Max text halo width is 1/4 of the
 *   font-size.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textHaloBlur The halo's fadeout distance towards the outside.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textFont Font stack to use for displaying text.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textSize Font size in SP or EM relative to 16sp.
 *
 *   Ignored if [textField] is not specified.
 *
 *   **Important:** If using zoom interpolation for text size, then all other properties defined in
 *   text units (like [textLetterSpacing], [textOffset], etc) MUST be defined in EM units, not SP
 *   units. This is a limitation of the MapLibre expression parser. If text size does not use zoom
 *   interpolation, then those other properties can be defined in either unit.
 *
 * @param textTransform Specifies how to capitalize text.
 * @param textLetterSpacing Text tracking amount.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textRotationAlignment In combination with [placement], determines the rotation behavior of
 *   the individual glyphs forming the text.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textPitchAlignment Orientation of text when map is pitched.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textMaxAngle Maximum angle change in degrees between adjacent characters.
 *
 *   Only applicable if [placement] is [SymbolPlacement.Line] or [SymbolPlacement.LineCenter]
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textMaxWidth The maximum line width for text wrapping.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textLineHeight Text leading value for multi-line text.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textJustify Text justification options.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textWritingMode The property allows control over a symbol's orientation. Note that the
 *   property values act as a hint, so that a symbol whose language doesn’t support the provided
 *   orientation will be laid out in its natural orientation. Example: English point symbol will be
 *   rendered horizontally even if array value contains single 'vertical' enum value. The order of
 *   elements in an array define priority order for the placement of an orientation variant.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textKeepUpright If true, the text may be flipped vertically to prevent it from being
 *   rendered upside-down.
 *
 *   Only applicable if [textRotationAlignment] is [TextRotationAlignment.Map] and [placement] is
 *   [SymbolPlacement.Line] or [SymbolPlacement.LineCenter]
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textRotate Rotates the text clockwise. Unit in degrees.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textAnchor Part of the text placed closest to the anchor.
 *
 *   Overridden by [textVariableAnchorOffset].
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textOffset Offset distance of text from its anchor in ems. Positive values indicate right
 *   and down, while negative values indicate left and up. If used with [textVariableAnchor], input
 *   values will be taken as absolute values. Offsets along the x- and y-axis will be applied
 *   automatically based on the anchor position.
 *
 *   Overridden by [textRadialOffset].
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textVariableAnchor To increase the chance of placing high-priority labels on the map, you
 *   can provide an array of [textAnchor] locations: the renderer will attempt to place the label at
 *   each location, in order, before moving onto the next label. Use [textJustify] =
 *   [TextJustify.Auto] to choose justification based on anchor position. To apply an offset, use
 *   the [textRadialOffset] or the two-dimensional [textOffset].
 *
 *   Only applicable if [placement] is [SymbolPlacement.Point].
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textRadialOffset Radial offset of text, in the direction of the symbol's anchor. Useful in
 *   combination with [textVariableAnchor], which defaults to using the two-dimensional [textOffset]
 *   if present.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textVariableAnchorOffset To increase the chance of placing high-priority labels on the
 *   map, you can provide an array of [SymbolAnchor] locations, each paired with an offset value.
 *   The renderer will attempt to place the label at each location, in order, before moving on to
 *   the next location+offset. Use [textJustify] = [TextJustify.Auto] to choose justification based
 *   on anchor position.
 *
 * Each anchor location is accompanied by a point which defines the offset when the corresponding
 * anchor location is used. Positive offset values indicate right and down, while negative values
 * indicate left and up. Anchor locations may repeat, allowing the renderer to try multiple offsets
 * to try and place a label using the same anchor.
 *
 * When present, this property takes precedence over [textAnchor], [textVariableAnchor],
 * [textOffset] and [textRadialOffset].
 *
 * Example:
 * ```kt
 * listOf(
 *   SymbolAnchor.Top to Point(0, 4),
 *   SymbolAnchor.Left to Point(3, 0),
 *   SymbolAnchor.Bottom to Point(1, 1)
 * )
 * ```
 *
 * When the renderer chooses the top anchor, [0, 4] will be used for [textOffset]; the text will be
 * shifted down by 4 ems. When the renderer chooses the left anchor, [3, 0] will be used for
 * [textOffset]; the text will be shifted right by 3 ems. Etc.
 *
 * Ignored if [textField] is not specified.
 *
 * NOTE: This property is currently not usable:
 * https://github.com/maplibre/maplibre-compose/issues/143
 *
 * @param textPadding Size of the additional area in dp around the text bounding box used for
 *   detecting symbol collisions.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textAllowOverlap If true, the text will be visible even if it collides with other
 *   previously drawn symbols.
 *
 *   Overridden by [textOverlap].
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textOverlap Controls whether to show an icon/text when it overlaps other symbols on the
 *   map. See [SymbolOverlap][org.maplibre.compose.expressions.value.SymbolOverlap]. Overrides
 *   [textAllowOverlap].
 *
 *   Ignored if [textField] is not specified.
 *
 *   **Note**: This property is not supported on native platforms, yet, see
 *   [maplibre-native#251](https://github.com/maplibre/maplibre-native/issues/251)**
 *
 * @param textIgnorePlacement If true, other symbols can be visible even if they collide with the
 *   text.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textOptional If true, icons will display without their corresponding text when the text
 *   collides with other symbols and the icon does not.
 *
 *   Ignored if not both [textField] and [iconImage] are specified.
 *
 * @param textTranslate Distance that the text's anchor is moved from its original placement in dp.
 *   Positive values indicate right and down, while negative values indicate left and up.
 *
 *   Ignored if [textField] is not specified.
 *
 * @param textTranslateAnchor Controls the frame of reference for [textTranslate].
 *
 *   Ignored if [textField] is not specified.
 *
 * @param onClick Function to call when any feature in this layer has been clicked.
 * @param onLongClick Function to call when any feature in this layer has been long-clicked.
 */
@Composable
@MaplibreComposable
public fun SymbolLayer(
  id: String,
  source: Source,
  sourceLayer: String = "",
  minZoom: Float = 0.0f,
  maxZoom: Float = 24.0f,
  filter: Expression<BooleanValue> = nil(),
  visible: Boolean = true,
  sortKey: Expression<FloatValue> = nil(),
  placement: Expression<SymbolPlacement> = const(SymbolPlacement.Point),
  spacing: Expression<DpValue> = const(250.dp),
  avoidEdges: Expression<BooleanValue> = const(false),
  zOrder: Expression<SymbolZOrder> = const(SymbolZOrder.Auto),

  // icon image
  iconImage: Expression<ImageValue> = nil(),

  // icon colors
  iconOpacity: Expression<FloatValue> = const(1f),
  iconColor: Expression<ColorValue> = const(Color.Black),
  iconHaloColor: Expression<ColorValue> = const(Color.Transparent),
  iconHaloWidth: Expression<DpValue> = const(0.dp),
  iconHaloBlur: Expression<DpValue> = const(0.dp),

  // icon layout
  iconSize: Expression<FloatValue> = const(1f),
  iconRotationAlignment: Expression<IconRotationAlignment> = const(IconRotationAlignment.Auto),
  iconPitchAlignment: Expression<IconPitchAlignment> = const(IconPitchAlignment.Auto),
  iconTextFit: Expression<IconTextFit> = const(IconTextFit.None),
  iconTextFitPadding: Expression<DpPaddingValue> = const(PaddingValues.Absolute(0.dp)),
  iconKeepUpright: Expression<BooleanValue> = const(false),
  iconRotate: Expression<FloatValue> = const(0f),

  // icon anchoring
  iconAnchor: Expression<SymbolAnchor> = const(SymbolAnchor.Center),
  iconOffset: Expression<DpOffsetValue> = const(DpOffset.Zero),

  // icon collision
  iconPadding: Expression<DpPaddingValue> = const(PaddingValues.Absolute(2.dp, 2.dp, 2.dp, 2.dp)),
  iconAllowOverlap: Expression<BooleanValue> = const(false),
  iconOverlap: Expression<StringValue> = nil(),
  iconIgnorePlacement: Expression<BooleanValue> = const(false),
  iconOptional: Expression<BooleanValue> = const(false),

  // icon translate
  iconTranslate: Expression<DpOffsetValue> = const(DpOffset.Zero),
  iconTranslateAnchor: Expression<TranslateAnchor> = const(TranslateAnchor.Map),

  // text content
  textField: Expression<FormattedValue> = const("").cast(),

  // text glyph colors
  textOpacity: Expression<FloatValue> = const(1f),
  textColor: Expression<ColorValue> = const(Color.Black),
  textHaloColor: Expression<ColorValue> = const(Color.Transparent),
  textHaloWidth: Expression<DpValue> = const(0.dp),
  textHaloBlur: Expression<DpValue> = const(0.dp),

  // text glyph properties
  textFont: Expression<ListValue<StringValue>> = LayerDefaults.FontNames,
  textSize: Expression<TextUnitValue> = const(1.em),
  textTransform: Expression<TextTransform> = const(TextTransform.None),
  textLetterSpacing: Expression<TextUnitValue> = const(0f.em),
  textRotationAlignment: Expression<TextRotationAlignment> = const(TextRotationAlignment.Auto),
  textPitchAlignment: Expression<TextPitchAlignment> = const(TextPitchAlignment.Auto),
  textMaxAngle: Expression<FloatValue> = const(45f),

  // text paragraph layout
  textMaxWidth: Expression<TextUnitValue> = const(10f.em),
  textLineHeight: Expression<TextUnitValue> = const(1.2f.em),
  textJustify: Expression<TextJustify> = const(TextJustify.Center),
  textWritingMode: Expression<ListValue<TextWritingMode>> = nil(),
  textKeepUpright: Expression<BooleanValue> = const(true),
  textRotate: Expression<FloatValue> = const(0f),

  // text anchoring
  textAnchor: Expression<SymbolAnchor> = const(SymbolAnchor.Center),
  textOffset: Expression<TextUnitOffsetValue> = offset(0f.em, 0f.em),
  textVariableAnchor: Expression<ListValue<SymbolAnchor>> = nil(),
  textRadialOffset: Expression<TextUnitValue> = const(0f.em),
  //  textVariableAnchorOffset: Expression<TextVariableAnchorOffsetValue> = nil(),
  textVariableAnchorOffset: Expression<Nothing> = nil(),

  // text collision
  textPadding: Expression<DpValue> = const(2.dp),
  textAllowOverlap: Expression<BooleanValue> = const(false),
  textOverlap: Expression<SymbolOverlap> = nil(),
  textIgnorePlacement: Expression<BooleanValue> = const(false),
  textOptional: Expression<BooleanValue> = const(false),

  // text translate
  textTranslate: Expression<DpOffsetValue> = const(DpOffset.Zero),
  textTranslateAnchor: Expression<TranslateAnchor> = const(TranslateAnchor.Map),
  onClick: FeaturesClickHandler? = null,
  onLongClick: FeaturesClickHandler? = null,
) {
  // Scaling code will need changes after https://github.com/maplibre/maplibre-native/issues/3057.
  val compileWithDpTextSize = rememberDpCompiler(LocalDensity.current.fontScale.dp)
  val compileWithEmTextSize = rememberEmCompiler(textSize)
  val compile = rememberPropertyCompiler()

  val compiledFilter = compile(filter)
  val compiledSortKey = compile(sortKey)
  val compiledSpacing = compile(spacing)
  val compiledAvoidEdges = compile(avoidEdges)
  val compiledZOrder = compile(zOrder)
  val compiledPlacement = compile(placement)

  val compiledIconImage = compile(iconImage)
  val compiledIconOpacity = compile(iconOpacity)
  val compiledIconColor = compile(iconColor)
  val compiledIconHaloColor = compile(iconHaloColor)
  val compiledIconHaloWidth = compile(iconHaloWidth)
  val compiledIconHaloBlur = compile(iconHaloBlur)
  val compiledIconSize = compile(iconSize)
  val compiledIconRotationAlignment = compile(iconRotationAlignment)
  val compiledIconPitchAlignment = compile(iconPitchAlignment)
  val compiledIconTextFit = compile(iconTextFit)
  val compiledIconTextFitPadding = compile(iconTextFitPadding)
  val compiledIconKeepUpright = compile(iconKeepUpright)
  val compiledIconRotate = compile(iconRotate)
  val compiledIconAnchor = compile(iconAnchor)
  val compiledIconOffset = compile(iconOffset)
  val compiledIconPadding = compile(iconPadding)
  val compiledIconAllowOverlap = compile(iconAllowOverlap)
  val compiledIconOverlap = compile(iconOverlap)
  val compiledIconIgnorePlacement = compile(iconIgnorePlacement)
  val compiledIconOptional = compile(iconOptional)
  val compiledIconTranslate = compile(iconTranslate)
  val compiledIconTranslateAnchor = compile(iconTranslateAnchor)

  val compiledTextField = compile(textField)
  val compiledTextOpacity = compile(textOpacity)
  val compiledTextColor = compile(textColor)
  val compiledTextHaloColor = compile(textHaloColor)
  val compiledTextHaloWidth = compile(textHaloWidth)
  val compiledTextHaloBlur = compile(textHaloBlur)
  val compiledTextFont = compile(textFont)
  val compiledTextSizeDp = compileWithDpTextSize(textSize)
  val compiledTextTransform = compile(textTransform)
  val compiledTextLetterSpacing = compileWithEmTextSize(textLetterSpacing)
  val compiledTextRotationAlignment = compile(textRotationAlignment)
  val compiledTextPitchAlignment = compile(textPitchAlignment)
  val compiledTextMaxAngle = compile(textMaxAngle)
  val compiledTextMaxWidth = compileWithEmTextSize(textMaxWidth)
  val compiledTextLineHeight = compileWithEmTextSize(textLineHeight)
  val compiledTextJustify = compile(textJustify)
  val compiledTextWritingMode = compile(textWritingMode)
  val compiledTextKeepUpright = compile(textKeepUpright)
  val compiledTextRotate = compile(textRotate)
  val compiledTextAnchor = compile(textAnchor)
  val compiledTextOffset = compileWithEmTextSize(textOffset)
  val compiledTextVariableAnchor = compile(textVariableAnchor)
  val compiledTextRadialOffset = compileWithEmTextSize(textRadialOffset)
  val compiledTextVariableAnchorOffset = compile(textVariableAnchorOffset)
  val compiledTextPadding = compile(textPadding)
  val compiledTextAllowOverlap = compile(textAllowOverlap)
  val compiledTextOverlap = compile(textOverlap)
  val compiledTextIgnorePlacement = compile(textIgnorePlacement)
  val compiledTextOptional = compile(textOptional)
  val compiledTextTranslate = compile(textTranslate)
  val compiledTextTranslateAnchor = compile(textTranslateAnchor)

  SourceReferenceEffect(source)
  LayerNode(
    factory = { SymbolLayer(id = id, source = source) },
    update = {
      set(sourceLayer) { layer.sourceLayer = it }
      set(minZoom) { layer.minZoom = it }
      set(maxZoom) { layer.maxZoom = it }
      set(compiledFilter) { layer.setFilter(it) }
      set(visible) { layer.visible = it }
      set(compiledPlacement) { layer.setSymbolPlacement(it) }
      set(compiledSpacing) { layer.setSymbolSpacing(it) }
      set(compiledAvoidEdges) { layer.setSymbolAvoidEdges(it) }
      set(compiledSortKey) { layer.setSymbolSortKey(it) }
      set(compiledZOrder) { layer.setSymbolZOrder(it) }

      set(compiledIconAllowOverlap) { layer.setIconAllowOverlap(it) }
      set(compiledIconOverlap) { layer.setIconOverlap(it) }
      set(compiledIconIgnorePlacement) { layer.setIconIgnorePlacement(it) }
      set(compiledIconOptional) { layer.setIconOptional(it) }
      set(compiledIconRotationAlignment) { layer.setIconRotationAlignment(it) }
      set(compiledIconSize) { layer.setIconSize(it) }
      set(compiledIconTextFit) { layer.setIconTextFit(it) }
      set(compiledIconTextFitPadding) { layer.setIconTextFitPadding(it) }
      set(compiledIconImage) { layer.setIconImage(it) }
      set(compiledIconRotate) { layer.setIconRotate(it) }
      set(compiledIconPadding) { layer.setIconPadding(it) }
      set(compiledIconKeepUpright) { layer.setIconKeepUpright(it) }
      set(compiledIconOffset) { layer.setIconOffset(it) }
      set(compiledIconAnchor) { layer.setIconAnchor(it) }
      set(compiledIconPitchAlignment) { layer.setIconPitchAlignment(it) }
      set(compiledIconOpacity) { layer.setIconOpacity(it) }
      set(compiledIconColor) { layer.setIconColor(it) }
      set(compiledIconHaloColor) { layer.setIconHaloColor(it) }
      set(compiledIconHaloWidth) { layer.setIconHaloWidth(it) }
      set(compiledIconHaloBlur) { layer.setIconHaloBlur(it) }
      set(compiledIconTranslate) { layer.setIconTranslate(it) }
      set(compiledIconTranslateAnchor) { layer.setIconTranslateAnchor(it) }

      set(compiledTextPitchAlignment) { layer.setTextPitchAlignment(it) }
      set(compiledTextRotationAlignment) { layer.setTextRotationAlignment(it) }
      set(compiledTextField) { layer.setTextField(it) }
      set(compiledTextFont) { layer.setTextFont(it) }
      set(compiledTextSizeDp.cast<DpValue>()) { layer.setTextSize(it) }
      set(compiledTextMaxWidth.cast<FloatValue>()) { layer.setTextMaxWidth(it) }
      set(compiledTextLineHeight.cast<FloatValue>()) { layer.setTextLineHeight(it) }
      set(compiledTextLetterSpacing.cast<FloatValue>()) { layer.setTextLetterSpacing(it) }
      set(compiledTextJustify) { layer.setTextJustify(it) }
      set(compiledTextRadialOffset.cast<FloatValue>()) { layer.setTextRadialOffset(it) }
      set(compiledTextVariableAnchor) { layer.setTextVariableAnchor(it) }
      set(compiledTextVariableAnchorOffset) { layer.setTextVariableAnchorOffset(it) }
      set(compiledTextAnchor) { layer.setTextAnchor(it) }
      set(compiledTextMaxAngle) { layer.setTextMaxAngle(it) }
      set(compiledTextWritingMode) { layer.setTextWritingMode(it) }
      set(compiledTextRotate) { layer.setTextRotate(it) }
      set(compiledTextPadding) { layer.setTextPadding(it) }
      set(compiledTextKeepUpright) { layer.setTextKeepUpright(it) }
      set(compiledTextTransform) { layer.setTextTransform(it) }
      set(compiledTextOffset.cast<FloatOffsetValue>()) { layer.setTextOffset(it) }
      set(compiledTextAllowOverlap) { layer.setTextAllowOverlap(it) }
      set(compiledTextOverlap) { layer.setTextOverlap(it) }
      set(compiledTextIgnorePlacement) { layer.setTextIgnorePlacement(it) }
      set(compiledTextOptional) { layer.setTextOptional(it) }
      set(compiledTextOpacity) { layer.setTextOpacity(it) }
      set(compiledTextColor) { layer.setTextColor(it) }
      set(compiledTextHaloColor) { layer.setTextHaloColor(it) }
      set(compiledTextHaloWidth) { layer.setTextHaloWidth(it) }
      set(compiledTextHaloBlur) { layer.setTextHaloBlur(it) }
      set(compiledTextTranslate) { layer.setTextTranslate(it) }
      set(compiledTextTranslateAnchor) { layer.setTextTranslateAnchor(it) }
    },
    onClick = onClick,
    onLongClick = onLongClick,
  )
}

internal expect class SymbolLayer(id: String, source: Source) : FeatureLayer {
  override var sourceLayer: String

  override fun setFilter(filter: CompiledExpression<BooleanValue>)

  fun setSymbolPlacement(placement: CompiledExpression<SymbolPlacement>)

  fun setSymbolSpacing(spacing: CompiledExpression<DpValue>)

  fun setSymbolAvoidEdges(avoidEdges: CompiledExpression<BooleanValue>)

  fun setSymbolSortKey(sortKey: CompiledExpression<FloatValue>)

  fun setSymbolZOrder(zOrder: CompiledExpression<SymbolZOrder>)

  fun setIconAllowOverlap(allowOverlap: CompiledExpression<BooleanValue>)

  fun setIconOverlap(overlap: CompiledExpression<StringValue>)

  fun setIconIgnorePlacement(ignorePlacement: CompiledExpression<BooleanValue>)

  fun setIconOptional(optional: CompiledExpression<BooleanValue>)

  fun setIconRotationAlignment(rotationAlignment: CompiledExpression<IconRotationAlignment>)

  fun setIconSize(size: CompiledExpression<FloatValue>)

  fun setIconTextFit(textFit: CompiledExpression<IconTextFit>)

  fun setIconTextFitPadding(textFitPadding: CompiledExpression<DpPaddingValue>)

  fun setIconImage(image: CompiledExpression<ImageValue>)

  fun setIconRotate(rotate: CompiledExpression<FloatValue>)

  fun setIconPadding(padding: CompiledExpression<DpPaddingValue>)

  fun setIconKeepUpright(keepUpright: CompiledExpression<BooleanValue>)

  fun setIconOffset(offset: CompiledExpression<DpOffsetValue>)

  fun setIconAnchor(anchor: CompiledExpression<SymbolAnchor>)

  fun setIconPitchAlignment(pitchAlignment: CompiledExpression<IconPitchAlignment>)

  fun setIconOpacity(opacity: CompiledExpression<FloatValue>)

  fun setIconColor(color: CompiledExpression<ColorValue>)

  fun setIconHaloColor(haloColor: CompiledExpression<ColorValue>)

  fun setIconHaloWidth(haloWidth: CompiledExpression<DpValue>)

  fun setIconHaloBlur(haloBlur: CompiledExpression<DpValue>)

  fun setIconTranslate(translate: CompiledExpression<DpOffsetValue>)

  fun setIconTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>)

  fun setTextPitchAlignment(pitchAlignment: CompiledExpression<TextPitchAlignment>)

  fun setTextRotationAlignment(rotationAlignment: CompiledExpression<TextRotationAlignment>)

  fun setTextField(field: CompiledExpression<FormattedValue>)

  fun setTextFont(font: CompiledExpression<ListValue<StringValue>>)

  fun setTextSize(size: CompiledExpression<DpValue>)

  fun setTextMaxWidth(maxWidth: CompiledExpression<FloatValue>)

  fun setTextLineHeight(lineHeight: CompiledExpression<FloatValue>)

  fun setTextLetterSpacing(letterSpacing: CompiledExpression<FloatValue>)

  fun setTextJustify(justify: CompiledExpression<TextJustify>)

  fun setTextRadialOffset(radialOffset: CompiledExpression<FloatValue>)

  fun setTextVariableAnchor(variableAnchor: CompiledExpression<ListValue<SymbolAnchor>>)

  fun setTextVariableAnchorOffset(
    variableAnchorOffset: CompiledExpression<TextVariableAnchorOffsetValue>
  )

  fun setTextAnchor(anchor: CompiledExpression<SymbolAnchor>)

  fun setTextMaxAngle(maxAngle: CompiledExpression<FloatValue>)

  fun setTextWritingMode(writingMode: CompiledExpression<ListValue<TextWritingMode>>)

  fun setTextRotate(rotate: CompiledExpression<FloatValue>)

  fun setTextPadding(padding: CompiledExpression<DpValue>)

  fun setTextKeepUpright(keepUpright: CompiledExpression<BooleanValue>)

  fun setTextTransform(transform: CompiledExpression<TextTransform>)

  fun setTextOffset(offset: CompiledExpression<FloatOffsetValue>)

  fun setTextAllowOverlap(allowOverlap: CompiledExpression<BooleanValue>)

  fun setTextOverlap(overlap: CompiledExpression<SymbolOverlap>)

  fun setTextIgnorePlacement(ignorePlacement: CompiledExpression<BooleanValue>)

  fun setTextOptional(optional: CompiledExpression<BooleanValue>)

  fun setTextOpacity(opacity: CompiledExpression<FloatValue>)

  fun setTextColor(color: CompiledExpression<ColorValue>)

  fun setTextHaloColor(haloColor: CompiledExpression<ColorValue>)

  fun setTextHaloWidth(haloWidth: CompiledExpression<DpValue>)

  fun setTextHaloBlur(haloBlur: CompiledExpression<DpValue>)

  fun setTextTranslate(translate: CompiledExpression<DpOffsetValue>)

  fun setTextTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>)
}
