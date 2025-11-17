package org.maplibre.compose.util

import android.graphics.PointF
import android.graphics.RectF
import android.view.Gravity
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.net.URI
import java.net.URISyntaxException
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.style.expressions.Expression as MLNExpression
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
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson

internal fun String.correctedAndroidUri(): String {
  return try {
    // tile URLs contain template params like {z}, {x}, {y}. These are illegal in a URI, so we need
    // to parse only the constant part of the URI and then append the template part after correction
    val partition = this.indexOf('{')
    val constPart = if (partition == -1) this else this.substring(0, partition)
    val templatePart = if (partition == -1) "" else this.substring(partition)
    val uri = URI(constPart)
    if (uri.scheme == "file" && uri.path.startsWith("/android_asset/"))
      URI("asset://${uri.path.removePrefix("/android_asset/")}").toString() + templatePart
    else this
  } catch (e: URISyntaxException) {
    e.printStackTrace()
    this
  }
}

internal fun DpOffset.toPointF(density: Density): PointF =
  with(density) { PointF(x.toPx(), y.toPx()) }

internal fun PointF.toOffset(density: Density): DpOffset =
  with(density) { DpOffset(x = x.toDp(), y = y.toDp()) }

internal fun DpRect.toRectF(density: Density): RectF =
  with(density) { RectF(left.toPx(), top.toPx(), right.toPx(), bottom.toPx()) }

internal fun LatLng.toPosition(): Position = Position(longitude = longitude, latitude = latitude)

internal fun Position.toLatLng(): LatLng = LatLng(latitude = latitude, longitude = longitude)

internal fun LatLngBounds.toBoundingBox(): BoundingBox =
  BoundingBox(northeast = northEast.toPosition(), southwest = southWest.toPosition())

internal fun BoundingBox.toLatLngBounds(): LatLngBounds =
  LatLngBounds.from(
    latNorth = northeast.latitude,
    lonEast = northeast.longitude,
    latSouth = southwest.latitude,
    lonWest = southwest.longitude,
  )

internal fun CompiledExpression<*>.toMLNExpression(): MLNExpression? =
  if (this == NullLiteral) null else MLNExpression.Converter.convert(normalizeJsonLike(false))

private fun buildLiteralArray(inLiteral: Boolean, block: JsonArray.() -> Unit): JsonArray {
  return if (inLiteral) {
    JsonArray().apply(block)
  } else {
    JsonArray(2).apply {
      add("literal")
      add(JsonArray().apply(block))
    }
  }
}

private fun buildLiteralObject(inLiteral: Boolean, block: JsonObject.() -> Unit): JsonObject {
  return if (inLiteral) {
    JsonObject().apply(block)
  } else {
    JsonObject().apply { add("literal", JsonObject().apply(block)) }
  }
}

private fun CompiledExpression<*>.normalizeJsonLike(inLiteral: Boolean): JsonElement =
  when (this) {
    NullLiteral -> JsonNull.INSTANCE
    is BooleanLiteral -> JsonPrimitive(value)
    is FloatLiteral -> JsonPrimitive(value)
    is StringLiteral -> JsonPrimitive(value)
    is OffsetLiteral ->
      buildLiteralArray(inLiteral) {
        add(value.x)
        add(value.y)
      }

    is ColorLiteral ->
      JsonPrimitive(
        value.toArgb().let {
          "rgba(${(it shr 16) and 0xFF}, ${(it shr 8) and 0xFF}, ${it and 0xFF}, ${value.alpha})"
        }
      )

    is DpPaddingLiteral ->
      buildLiteralArray(inLiteral) {
        add(value.calculateTopPadding().value)
        add(value.calculateRightPadding(LayoutDirection.Ltr).value)
        add(value.calculateBottomPadding().value)
        add(value.calculateLeftPadding(LayoutDirection.Ltr).value)
      }

    is CompiledFunctionCall ->
      JsonArray(args.size + 1).apply {
        add(name)
        args.forEachIndexed { i, v -> add(v.normalizeJsonLike(inLiteral || isLiteralArg(i))) }
      }

    is CompiledListLiteral<*> ->
      buildLiteralArray(inLiteral) { value.forEach { add(it.normalizeJsonLike(true)) } }

    is CompiledMapLiteral<*> ->
      buildLiteralObject(inLiteral) {
        value.forEach { (k, v) -> add(k, v.normalizeJsonLike(true)) }
      }

    is CompiledOptions<*> ->
      JsonObject().apply { value.forEach { (k, v) -> add(k, v.normalizeJsonLike(inLiteral)) } }
  }

internal fun Alignment.toGravity(layoutDir: LayoutDirection): Int {
  val (x, y) = align(IntSize(1, 1), IntSize(3, 3), layoutDir)
  val h =
    when (x) {
      0 -> Gravity.LEFT
      1 -> Gravity.CENTER_HORIZONTAL
      2 -> Gravity.RIGHT
      else -> error("Invalid alignment")
    }
  val v =
    when (y) {
      0 -> Gravity.TOP
      1 -> Gravity.CENTER_VERTICAL
      2 -> Gravity.BOTTOM
      else -> error("Invalid alignment")
    }
  return h or v
}

internal fun Geometry.toMlnGeometry(): org.maplibre.geojson.Geometry {
  return when (this) {
    is Point -> org.maplibre.geojson.Point.fromJson(toJson())
    is GeometryCollection<*> -> org.maplibre.geojson.GeometryCollection.fromJson(toJson())
    is LineString -> org.maplibre.geojson.LineString.fromJson(toJson())
    is MultiLineString -> org.maplibre.geojson.MultiLineString.fromJson(toJson())
    is MultiPoint -> org.maplibre.geojson.MultiPoint.fromJson(toJson())
    is MultiPolygon -> org.maplibre.geojson.MultiPolygon.fromJson(toJson())
    is Polygon -> org.maplibre.geojson.Polygon.fromJson(toJson())
  }
}

internal fun Feature<*, kotlinx.serialization.json.JsonObject?>.toMLNFeature():
  org.maplibre.geojson.Feature {
  return org.maplibre.geojson.Feature.fromJson(toJson())
}

internal fun org.maplibre.geojson.FeatureCollection.toSpatialKFeatureCollection():
  FeatureCollection<Geometry, kotlinx.serialization.json.JsonObject?> {
  return FeatureCollection.fromJson(toJson())
}

internal fun PositionQuad.toLatLngQuad() =
  LatLngQuad(
    topRight = this.topRight.toLatLng(),
    topLeft = this.topLeft.toLatLng(),
    bottomLeft = this.bottomLeft.toLatLng(),
    bottomRight = this.bottomRight.toLatLng(),
  )
