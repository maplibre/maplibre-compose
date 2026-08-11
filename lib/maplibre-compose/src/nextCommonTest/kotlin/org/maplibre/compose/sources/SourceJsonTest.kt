package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

class SourceJsonTest {

  @Test
  fun a_tile_set_writes_the_shared_tile_json_fields() {
    val json = buildJsonObject {
      putTileSetOptions(
        TileSetOptions(
          minZoom = 3,
          maxZoom = 14,
          tileCoordinateSystem = TileCoordinateSystem.TMS,
          boundingBox = BoundingBox(Position(-10.0, -20.0), Position(30.0, 40.0)),
          attributionHtml = "© someone",
        )
      )
    }

    assertEquals(3, json["minzoom"]?.jsonPrimitive?.content?.toInt())
    assertEquals(14, json["maxzoom"]?.jsonPrimitive?.content?.toInt())
    assertEquals("tms", json["scheme"]?.jsonPrimitive?.content)
    assertEquals("© someone", json["attribution"]?.jsonPrimitive?.content)
    assertEquals(
      listOf(-10.0, -20.0, 30.0, 40.0),
      (json["bounds"] as JsonArray).map { it.jsonPrimitive.content.toDouble() },
      "bounds are west, south, east, north",
    )
  }

  @Test
  fun a_tile_set_omits_what_it_was_not_given() {
    val json = buildJsonObject { putTileSetOptions(TileSetOptions()) }

    assertEquals("xyz", json["scheme"]?.jsonPrimitive?.content, "the spec's default scheme")
    assertNull(json["bounds"], "no bounding box means no bounds key")
    assertNull(json["attribution"], "no attribution means no attribution key")
  }

  @Test
  fun a_raster_source_writes_its_own_keys_and_its_tile_set_s() {
    val json =
      RasterSource(
          id = "tiles",
          tiles = listOf("https://example.invalid/{z}/{x}/{y}.png"),
          options =
            TileSetOptions(
              minZoom = 2,
              maxZoom = 12,
              tileCoordinateSystem = TileCoordinateSystem.TMS,
              boundingBox = BoundingBox(Position(-10.0, -20.0), Position(30.0, 40.0)),
              attributionHtml = "© someone",
            ),
          tileSize = 512,
        )
        .toJson()

    assertEquals(
      setOf("type", "tiles", "tileSize", "minzoom", "maxzoom", "scheme", "bounds", "attribution"),
      json.keys,
    )
    assertEquals("raster", json["type"]?.jsonPrimitive?.content)
    assertEquals(
      listOf("https://example.invalid/{z}/{x}/{y}.png"),
      (json["tiles"] as JsonArray).map { it.jsonPrimitive.content },
    )
    assertEquals("512", json["tileSize"]?.jsonPrimitive?.content, "tileSize, not tilesize")
  }

  @Test
  fun geojson_options_stay_inside_the_style_spec() {
    val json = buildJsonObject { putGeoJsonOptions(GeoJsonOptions()) }

    // The spec has no minzoom on a GeoJSON source: tiling always starts at zero. MapLibre GL JS
    // rejects the source over it, so only the MapLibre Native platforms write it.
    assertFalse("minzoom" in json, "minzoom is not a style-spec key on a GeoJSON source")
    assertFalse("synchronousUpdate" in json, "synchronousUpdate is a MapLibre Native extension")
  }

  @Test
  fun geojson_cluster_properties_are_written_operator_first() {
    val json = buildJsonObject {
      putGeoJsonOptions(
        GeoJsonOptions(
          cluster = true,
          clusterProperties =
            mapOf(
              "total" to
                GeoJsonOptions.ClusterPropertyAggregator(
                  mapper = org.maplibre.compose.expressions.dsl.const(1),
                  reducer = org.maplibre.compose.expressions.dsl.const(2),
                )
            ),
        )
      )
    }

    val pair = (json["clusterProperties"] as kotlinx.serialization.json.JsonObject)["total"]
    // By value rather than by text: a whole number renders as `2.0` on the JVM and `2` in
    // JavaScript.
    val values = (pair as JsonArray).map { it.jsonPrimitive.content.toDouble() }
    assertEquals(
      listOf(2.0, 1.0),
      values,
      "the style spec's pair is [operator, map expression], so the reducer comes first",
    )
    assertEquals(JsonPrimitive(true), json["cluster"])
  }
}
