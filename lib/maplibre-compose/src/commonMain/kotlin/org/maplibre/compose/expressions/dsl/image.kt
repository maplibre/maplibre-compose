package org.maplibre.compose.expressions.dsl

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import org.maplibre.compose.expressions.ast.BitmapLiteral
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.util.ImageResizeOptions

/**
 * Returns an image type for use in `iconImage` (see
 * [SymbolLayer][org.maplibre.compose.layers.SymbolLayer]), `pattern` entries (see
 * [BackgroundLayer][org.maplibre.compose.layers.BackgroundLayer],
 * [FillLayer][org.maplibre.compose.layers.FillLayer],
 * [FillExtrusionLayer][org.maplibre.compose.layers.FillExtrusionLayer],
 * [LineLayer][org.maplibre.compose.layers.LineLayer]) and as a section in the [format] expression.
 *
 * If set, the image argument will check that the requested image exists in the style and will
 * return either the resolved image name or `null`, depending on whether or not the image is
 * currently in the style. This validation process is synchronous and requires the image to have
 * been added to the style before requesting it in the image argument.
 */
public fun image(value: Expression<StringValue>): Expression<ImageValue> =
  FunctionCall.of("image", value).cast()

/**
 * Returns an image type for use in `iconImage` (see
 * [SymbolLayer][org.maplibre.compose.layers.SymbolLayer]), `pattern` entries (see
 * [BackgroundLayer][org.maplibre.compose.layers.BackgroundLayer],
 * [FillLayer][org.maplibre.compose.layers.FillLayer],
 * [FillExtrusionLayer][org.maplibre.compose.layers.FillExtrusionLayer],
 * [LineLayer][org.maplibre.compose.layers.LineLayer]) and as a section in the [format] expression.
 *
 * The image argument will check that the requested image exists in the style and will return either
 * the resolved image name or `null`, depending on whether or not the image is currently in the
 * style. This validation process is synchronous and requires the image to have been added to the
 * style before requesting it in the image argument.
 */
public fun image(value: String): Expression<ImageValue> = image(const(value))

/**
 * Returns an image type for use in `iconImage` (see
 * [SymbolLayer][org.maplibre.compose.layers.SymbolLayer]), `pattern` entries (see
 * [BackgroundLayer][org.maplibre.compose.layers.BackgroundLayer],
 * [FillLayer][org.maplibre.compose.layers.FillLayer],
 * [FillExtrusionLayer][org.maplibre.compose.layers.FillExtrusionLayer],
 * [LineLayer][org.maplibre.compose.layers.LineLayer]) and as a section in the [format] expression.
 *
 * The [ImageBitmap] will be registered with the style when it's referenced by a layer, and
 * unregistered from the style if it's no longer referenced by any layer. An ID referencing the
 * bitmap will be generated automatically and inserted into the expression.
 *
 * @param isSdf Should be set to true if the bitmap is a
 *   [Signed Distance Field](https://docs.mapbox.com/help/troubleshooting/using-recolorable-images-in-mapbox-maps/)
 */
public fun image(
  value: ImageBitmap,
  isSdf: Boolean = false,
  resizeOptions: ImageResizeOptions? = null,
): Expression<ImageValue> =
  FunctionCall.of("image", BitmapLiteral.of(value, isSdf, resizeOptions)).cast()

/**
 * Returns an image type for use in `iconImage` (see
 * [SymbolLayer][org.maplibre.compose.layers.SymbolLayer]), `pattern` entries (see
 * [BackgroundLayer][org.maplibre.compose.layers.BackgroundLayer],
 * [FillLayer][org.maplibre.compose.layers.FillLayer],
 * [FillExtrusionLayer][org.maplibre.compose.layers.FillExtrusionLayer],
 * [LineLayer][org.maplibre.compose.layers.LineLayer]) and as a section in the [format] expression.
 *
 * The [Painter] will be drawn to an [ImageBitmap] and registered with the style when it's
 * referenced by a layer, and unregistered from the style if it's no longer referenced by any layer.
 * An ID referencing the bitmap will be generated automatically and inserted into the expression.
 *
 * The bitmap will be created with the provided [size], or the intrinsic size of the painter if not
 * provided, or 16x16 DP if the painter has no intrinsic size.
 *
 * @param drawAsSdf If true, will draw the image to a bitmap as a
 *   [Signed Distance Field](https://docs.mapbox.com/help/troubleshooting/using-recolorable-images-in-mapbox-maps/).
 *   Ideal for monochrome vector icons.
 */
public fun image(
  value: Painter,
  size: DpSize? = null,
  drawAsSdf: Boolean = false,
  resizeOptions: ImageResizeOptions? = null,
): Expression<ImageValue> =
  FunctionCall.of("image", PainterLiteral.of(value, size, drawAsSdf, resizeOptions)).cast()
