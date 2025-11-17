package org.maplibre.compose.util

import MapLibre.MLNCoordinateBounds
import MapLibre.MLNCoordinateBoundsMake
import MapLibre.MLNCoordinateQuadMake
import MapLibre.MLNFeatureProtocol
import MapLibre.MLNOrnamentPosition
import MapLibre.MLNOrnamentPositionBottomLeft
import MapLibre.MLNOrnamentPositionBottomRight
import MapLibre.MLNOrnamentPositionTopLeft
import MapLibre.MLNOrnamentPositionTopRight
import MapLibre.MLNPointFeatureCluster
import MapLibre.MLNShape
import MapLibre.expressionWithMLNJSONObject
import MapLibre.predicateWithMLNJSONObject
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.CValue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Image
import org.maplibre.compose.expressions.ast.BooleanLiteral
import org.maplibre.compose.expressions.ast.ColorLiteral
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.CompiledFunctionCall
import org.maplibre.compose.expressions.ast.CompiledListLiteral
import org.maplibre.compose.expressions.ast.CompiledMapLiteral
import org.maplibre.compose.expressions.ast.CompiledOptions
import org.maplibre.compose.expressions.ast.DpPaddingLiteral
import org.maplibre.compose.expressions.ast.FloatLiteral
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.expressions.ast.OffsetLiteral
import org.maplibre.compose.expressions.ast.StringLiteral
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.GeoJsonObject
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGVectorMake
import platform.CoreLocation.CLLocationCoordinate2D
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.Foundation.NSData
import platform.Foundation.NSExpression
import platform.Foundation.NSPredicate
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSValue
import platform.Foundation.dataWithBytes
import platform.UIKit.UIColor
import platform.UIKit.UIEdgeInsetsMake
import platform.UIKit.UIImage
import platform.UIKit.UIImageRenderingMode
import platform.UIKit.UIImageResizingModeStretch
import platform.UIKit.valueWithCGVector
import platform.UIKit.valueWithUIEdgeInsets
import platform.posix.memcpy

internal fun ByteArray.toNSData(): NSData {
  return if (isEmpty()) NSData()
  else usePinned { NSData.dataWithBytes(it.addressOf(0), it.get().size.toULong()) }
}

internal fun NSData.toByteArray(): ByteArray {
  return if (length.toInt() == 0) ByteArray(0)
  else ByteArray(length.toInt()).apply { usePinned { memcpy(it.addressOf(0), bytes, length) } }
}

internal fun MLNFeatureProtocol.toFeature(): Feature<Geometry, JsonObject?> {
  return Feature.fromJson(JsonElement.convert(geoJSONDictionary()).toString())
}

internal fun JsonElement.Companion.convert(any: Any?): JsonElement {
  return when (any) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(any)
    is Number -> JsonPrimitive(any)
    is String -> JsonPrimitive(any)
    is List<*> -> JsonArray(any.map { convert(it) })
    is Map<*, *> -> JsonObject(any.entries.associate { (k, v) -> k as String to convert(v) })
    else -> error("Unsupported type: ${any::class.simpleName}")
  }
}

internal fun CValue<CGPoint>.toDpOffset(): DpOffset = useContents { DpOffset(x = x.dp, y = y.dp) }

internal fun DpOffset.toCGPoint(): CValue<CGPoint> =
  CGPointMake(x = x.value.toDouble(), y = y.value.toDouble())

internal fun DpRect.toCGRect(): CValue<CGRect> =
  CGRectMake(
    x = left.value.toDouble(),
    y = top.value.toDouble(),
    width = (right - left).value.toDouble(),
    height = (bottom - top).value.toDouble(),
  )

internal fun CValue<CLLocationCoordinate2D>.toPosition(): Position = useContents { toPosition() }

internal fun CLLocationCoordinate2D.toPosition(): Position =
  Position(longitude = longitude, latitude = latitude)

internal fun Position.toCLLocationCoordinate2D(): CValue<CLLocationCoordinate2D> =
  CLLocationCoordinate2DMake(latitude = latitude, longitude = longitude)

internal fun CValue<MLNCoordinateBounds>.toBoundingBox(): BoundingBox = useContents {
  BoundingBox(northeast = ne.toPosition(), southwest = sw.toPosition())
}

internal fun BoundingBox.toMLNCoordinateBounds(): CValue<MLNCoordinateBounds> =
  MLNCoordinateBoundsMake(
    ne = northeast.toCLLocationCoordinate2D(),
    sw = southwest.toCLLocationCoordinate2D(),
  )

internal fun GeoJsonObject.toMLNShape(): MLNShape {
  return MLNShape.shapeWithData(
    data = toJson().encodeToByteArray().toNSData(),
    encoding = NSUTF8StringEncoding,
    error = null,
  )!!
}

internal fun String.toMLNShape(): MLNShape {
  return MLNShape.shapeWithData(
    data = encodeToByteArray().toNSData(),
    encoding = NSUTF8StringEncoding,
    error = null,
  )!!
}

internal fun Feature<*, JsonObject?>.toMLNPointFeatureCluster(): MLNPointFeatureCluster {
  val feature =
    MLNPointFeatureCluster.shapeWithData(
      data = toJson().encodeToByteArray().toNSData(),
      encoding = NSUTF8StringEncoding,
      error = null,
    ) as MLNFeatureProtocol

  val cluster = MLNPointFeatureCluster()
  cluster.identifier = feature.identifier
  cluster.attributes = feature.attributes
  return cluster
}

internal fun CompiledExpression<*>.toNSExpression(): NSExpression =
  if (this == NullLiteral) NSExpression.expressionForConstantValue(null)
  else NSExpression.expressionWithMLNJSONObject(normalizeJsonLike(false)!!)

internal fun CompiledExpression<BooleanValue>.toNSPredicate(): NSPredicate? =
  if (this == NullLiteral) null
  else NSPredicate.predicateWithMLNJSONObject(normalizeJsonLike(false)!!)

private fun buildLiteralList(inLiteral: Boolean, block: MutableList<Any?>.() -> Unit): List<Any?> {
  return if (inLiteral) {
    buildList { block() }
  } else {
    buildList {
      add("literal")
      add(buildList { block() })
    }
  }
}

private fun buildLiteralMap(
  inLiteral: Boolean,
  block: MutableMap<String, Any?>.() -> Unit,
): Map<String, *> {
  return if (inLiteral) {
    buildMap { block() }
  } else {
    buildMap { put("literal", buildMap { block() }) }
  }
}

private fun CompiledExpression<*>.normalizeJsonLike(inLiteral: Boolean): Any? =
  when (this) {
    NullLiteral -> null
    is BooleanLiteral -> value
    is FloatLiteral -> value
    is StringLiteral -> value
    is OffsetLiteral ->
      NSValue.valueWithCGVector(CGVectorMake(value.x.toDouble(), value.y.toDouble()))

    is ColorLiteral ->
      UIColor.colorWithRed(
        red = value.red.toDouble(),
        green = value.green.toDouble(),
        blue = value.blue.toDouble(),
        alpha = value.alpha.toDouble(),
      )

    is DpPaddingLiteral ->
      NSValue.valueWithUIEdgeInsets(
        UIEdgeInsetsMake(
          top = value.calculateTopPadding().value.toDouble(),
          left = value.calculateLeftPadding(LayoutDirection.Ltr).value.toDouble(),
          bottom = value.calculateBottomPadding().value.toDouble(),
          right = value.calculateRightPadding(LayoutDirection.Ltr).value.toDouble(),
        )
      )

    is CompiledFunctionCall ->
      buildList {
        add(name)
        args.forEachIndexed { i, v -> add(v.normalizeJsonLike(inLiteral || isLiteralArg(i))) }
      }

    is CompiledListLiteral<*> ->
      buildLiteralList(inLiteral) { value.forEach { add(it.normalizeJsonLike(true)) } }

    is CompiledMapLiteral<*> ->
      buildLiteralMap(inLiteral) { value.forEach { (k, v) -> put(k, v.normalizeJsonLike(true)) } }

    is CompiledOptions<*> ->
      buildMap { value.forEach { (k, v) -> put(k, v.normalizeJsonLike(inLiteral)) } }
  }

internal fun Alignment.toMLNOrnamentPosition(layoutDir: LayoutDirection): MLNOrnamentPosition {
  return when (align(IntSize(1, 1), IntSize(2, 2), layoutDir)) {
    IntOffset(0, 0) -> MLNOrnamentPositionTopLeft
    IntOffset(1, 0) -> MLNOrnamentPositionTopRight
    IntOffset(0, 1) -> MLNOrnamentPositionBottomLeft
    IntOffset(1, 1) -> MLNOrnamentPositionBottomRight
    else -> error("Invalid alignment")
  }
}

internal fun ImageBitmap.toUIImage(
  scale: Float,
  sdf: Boolean,
  resizeOptions: ImageResizeOptions?,
): UIImage {
  var image =
    UIImage(
        data = Image.makeFromBitmap(this.asSkiaBitmap()).encodeToData()!!.bytes.toNSData(),
        scale = scale.toDouble(),
      )
      .imageWithRenderingMode(
        if (sdf) UIImageRenderingMode.UIImageRenderingModeAlwaysTemplate
        else UIImageRenderingMode.UIImageRenderingModeAutomatic
      )

  if (resizeOptions != null)
    image =
      image.size.useContents {
        image.resizableImageWithCapInsets(
          UIEdgeInsetsMake(
            top = resizeOptions.top.value.toDouble(),
            left = resizeOptions.left.value.toDouble(),
            bottom = resizeOptions.bottom.value.toDouble(),
            right = resizeOptions.right.value.toDouble(),
          ),
          UIImageResizingModeStretch,
        )
      }

  return image
}

internal fun ImageBitmap.toUIImage() =
  UIImage(data = Image.makeFromBitmap(this.asSkiaBitmap()).encodeToData()!!.bytes.toNSData())
    .imageWithRenderingMode(UIImageRenderingMode.UIImageRenderingModeAutomatic)

internal fun PositionQuad.toMLNCoordinateQuad() =
  MLNCoordinateQuadMake(
    topLeft = topLeft.toCLLocationCoordinate2D(),
    topRight = topRight.toCLLocationCoordinate2D(),
    bottomLeft = bottomLeft.toCLLocationCoordinate2D(),
    bottomRight = bottomRight.toCLLocationCoordinate2D(),
  )
