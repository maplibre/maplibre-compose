package org.maplibre.compose.editing

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

internal fun id(value: String): JsonPrimitive = JsonPrimitive(value)

internal fun pos(lon: Double, lat: Double): Position = Position(lon, lat)

internal fun feature(
  geometry: Geometry,
  id: String? = null,
  properties: JsonObject? = null,
): EditorFeature = Feature(geometry, properties, id?.let(::id))

internal fun square(
  id: String? = null,
  origin: Double = 0.0,
  size: Double = 10.0,
  originLat: Double = origin,
): EditorFeature = feature(squareGeometry(origin, size, originLat), id)

internal fun squareGeometry(
  origin: Double = 0.0,
  size: Double = 10.0,
  originLat: Double = origin,
): Polygon =
  Polygon(
    listOf(
      listOf(
        pos(origin, originLat),
        pos(origin + size, originLat),
        pos(origin + size, originLat + size),
        pos(origin, originLat + size),
        pos(origin, originLat),
      )
    )
  )

internal fun line(id: String? = null, vararg positions: Position): EditorFeature =
  feature(LineString(positions.toList()), id)

internal fun point(id: String? = null, lon: Double = 0.0, lat: Double = 0.0): EditorFeature =
  feature(Point(pos(lon, lat)), id)

/** A flat map: one dp is a tenth of a degree, y grows south. */
internal fun unproject(screen: DpOffset): Position =
  pos(screen.x.value / 10.0, -screen.y.value / 10.0)

internal fun screenOf(lon: Double, lat: Double): DpOffset = DpOffset((lon * 10).dp, (-lat * 10).dp)

internal fun FeatureEditorState.hitsAt(
  lon: Double,
  lat: Double,
  radius: Double = 2.0,
  fill: Boolean = true,
): List<EditorHit> = hitTest(screenOf(lon, lat), radius.dp, ::unproject, fill)

/** Compares positions to a billionth of a degree: Mercator round trips are not exact. */
internal fun assertPositionEquals(expected: Position, actual: Position?, message: String? = null) {
  assertNotNull(actual, message)
  assertEquals(expected.longitude, actual.longitude, 1e-9, message)
  assertEquals(expected.latitude, actual.latitude, 1e-9, message)
}
