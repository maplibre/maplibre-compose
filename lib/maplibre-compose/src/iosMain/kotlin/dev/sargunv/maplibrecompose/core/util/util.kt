package dev.sargunv.maplibrecompose.core.util

import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cocoapods.MapLibre.MLNAltitudeForZoomLevel
import cocoapods.MapLibre.MLNCoordinateBounds
import cocoapods.MapLibre.MLNCoordinateBoundsMake
import cocoapods.MapLibre.MLNFeatureProtocol
import cocoapods.MapLibre.MLNMapCamera
import cocoapods.MapLibre.MLNOrnamentPosition
import cocoapods.MapLibre.MLNOrnamentPositionBottomLeft
import cocoapods.MapLibre.MLNOrnamentPositionBottomRight
import cocoapods.MapLibre.MLNOrnamentPositionTopLeft
import cocoapods.MapLibre.MLNOrnamentPositionTopRight
import cocoapods.MapLibre.MLNShape
import cocoapods.MapLibre.expressionWithMLNJSONObject
import cocoapods.MapLibre.predicateWithMLNJSONObject
import dev.sargunv.maplibrecompose.core.CameraPosition
import dev.sargunv.maplibrecompose.expressions.ast.BooleanLiteral
import dev.sargunv.maplibrecompose.expressions.ast.ColorLiteral
import dev.sargunv.maplibrecompose.expressions.ast.CompiledExpression
import dev.sargunv.maplibrecompose.expressions.ast.CompiledFunctionCall
import dev.sargunv.maplibrecompose.expressions.ast.CompiledListLiteral
import dev.sargunv.maplibrecompose.expressions.ast.CompiledMapLiteral
import dev.sargunv.maplibrecompose.expressions.ast.CompiledOptions
import dev.sargunv.maplibrecompose.expressions.ast.DpPaddingLiteral
import dev.sargunv.maplibrecompose.expressions.ast.FloatLiteral
import dev.sargunv.maplibrecompose.expressions.ast.NullLiteral
import dev.sargunv.maplibrecompose.expressions.ast.OffsetLiteral
import dev.sargunv.maplibrecompose.expressions.ast.StringLiteral
import dev.sargunv.maplibrecompose.expressions.value.BooleanValue
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.GeoJson
import io.github.dellisd.spatialk.geojson.Position
import kotlinx.cinterop.CValue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGDataProviderCopyData
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreateCopyWithColorSpace
import platform.CoreGraphics.CGImageGetAlphaInfo
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
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
import platform.UIKit.valueWithCGVector
import platform.UIKit.valueWithUIEdgeInsets

internal fun ByteArray.toNSData(): NSData {
  return usePinned { NSData.dataWithBytes(it.addressOf(0), it.get().size.toULong()) }
}

internal fun MLNFeatureProtocol.toFeature(): Feature {
  return Feature.fromJson(JsonElement.convert(geoJSONDictionary()) as JsonObject)
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

internal fun CameraPosition.toMLNMapCamera(size: CValue<CGSize>): MLNMapCamera {
  return MLNMapCamera().let {
    it.centerCoordinate = target.toCLLocationCoordinate2D()
    it.pitch = tilt
    it.heading = bearing
    it.altitude =
      MLNAltitudeForZoomLevel(
        zoomLevel = zoom,
        pitch = tilt,
        latitude = target.latitude,
        size = size,
      )
    it
  }
}

internal fun GeoJson.toMLNShape(): MLNShape {
  return MLNShape.shapeWithData(
    data = json().encodeToByteArray().toNSData(),
    encoding = NSUTF8StringEncoding,
    error = null,
  )!!
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

internal fun ImageBitmap.toUIImage(scale: Float, sdf: Boolean) =
  UIImage(
      data = Image.makeFromBitmap(this.asSkiaBitmap()).encodeToData()!!.bytes.toNSData(),
      scale = scale.toDouble(),
    )
    .imageWithRenderingMode(
      if (sdf) UIImageRenderingMode.UIImageRenderingModeAlwaysTemplate
      else UIImageRenderingMode.UIImageRenderingModeAutomatic
    )

internal fun UIImage.toImageBitmap(): ImageBitmap {
  val skiaImage = this.toSkiaImage() ?: return ImageBitmap(1, 1)
  return skiaImage.toComposeImageBitmap()
}

private fun UIImage.toSkiaImage(): Image? {
  val imageRef =
    CGImageCreateCopyWithColorSpace(this.CGImage, CGColorSpaceCreateDeviceRGB()) ?: return null

  val width = CGImageGetWidth(imageRef).toInt()
  val height = CGImageGetHeight(imageRef).toInt()

  val bytesPerRow = CGImageGetBytesPerRow(imageRef)
  val data = CGDataProviderCopyData(CGImageGetDataProvider(imageRef))
  val bytePointer = CFDataGetBytePtr(data)
  val length = CFDataGetLength(data)
  val alphaInfo = CGImageGetAlphaInfo(imageRef)

  val alphaType =
    when (alphaInfo) {
      CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst,
      CGImageAlphaInfo.kCGImageAlphaPremultipliedLast -> ColorAlphaType.PREMUL
      CGImageAlphaInfo.kCGImageAlphaFirst,
      CGImageAlphaInfo.kCGImageAlphaLast -> ColorAlphaType.UNPREMUL
      CGImageAlphaInfo.kCGImageAlphaNone,
      CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst,
      CGImageAlphaInfo.kCGImageAlphaNoneSkipLast -> ColorAlphaType.OPAQUE
      else -> ColorAlphaType.UNKNOWN
    }

  val byteArray = ByteArray(length.toInt()) { index -> bytePointer!![index].toByte() }

  CFRelease(data)
  CFRelease(imageRef)

  val skiaColorSpace = ColorSpace.sRGB
  val colorType = ColorType.RGBA_8888

  // Convert RGBA to BGRA
  for (i in byteArray.indices step 4) {
    val r = byteArray[i]
    val g = byteArray[i + 1]
    val b = byteArray[i + 2]
    val a = byteArray[i + 3]

    byteArray[i] = b
    byteArray[i + 2] = r
  }

  return Image.makeRaster(
    imageInfo =
      ImageInfo(
        width = width,
        height = height,
        colorType = colorType,
        alphaType = alphaType,
        colorSpace = skiaColorSpace,
      ),
    bytes = byteArray,
    rowBytes = bytesPerRow.toInt(),
  )
}
