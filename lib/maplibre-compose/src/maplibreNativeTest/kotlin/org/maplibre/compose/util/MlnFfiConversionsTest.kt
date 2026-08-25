package org.maplibre.compose.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.nativeffi.query.QueriedFeature
import org.maplibre.spatialk.geojson.Feature as GeoJsonFeature
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

class MlnFfiConversionsTest {

  @Test
  fun unsigned_feature_id_remains_a_json_number() {
    val feature =
      queriedFeature(
          """{"type":"Feature","id":9223372036854775808,"geometry":{"type":"Point","coordinates":[0.0,0.0]},"properties":{}}"""
        )
        .toGeoJsonFeature()

    val id = requireNotNull(feature).id as JsonPrimitive
    assertFalse(id.isString)
    assertEquals("9223372036854775808", id.content)
  }

  @Test
  fun queried_feature_metadata_stays_off_the_geojson_feature() {
    val feature =
      queriedFeature(
          json =
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[1.0,2.0]},"properties":{"name":"a"}}""",
          sourceId = "s",
          sourceLayerId = "l",
          state = """{"selected":true}""",
        )
        .toGeoJsonFeature()

    assertEquals(Point(Position(1.0, 2.0)), requireNotNull(feature).geometry)
    assertEquals(buildJsonObject { put("name", "a") }, feature.properties)
  }

  @Test
  fun cluster_conversion_types_only_cluster_id_and_preserves_dollar_prefixed_properties() {
    val feature =
      GeoJsonFeature(
        geometry = GeometryCollection(emptyList()),
        properties =
          buildJsonObject {
            put("cluster_id", 42)
            put("\$source", "caller-source")
            put("\$state", "caller-state")
            put("name", "cluster")
          },
        id = null,
      )

    val converted = requireNotNull(feature.toFfiClusterFeature()).toJsonElement() as JsonObject
    val properties = assertIs<JsonObject>(converted["properties"])

    val clusterId = assertIs<JsonPrimitive>(properties["cluster_id"])
    assertFalse(clusterId.isString)
    assertEquals("42", clusterId.content)
    assertEquals(JsonPrimitive("caller-source"), properties["\$source"])
    assertEquals(JsonPrimitive("caller-state"), properties["\$state"])
    assertEquals(JsonPrimitive("cluster"), properties["name"])
  }

  private fun queriedFeature(
    json: String,
    sourceId: String? = null,
    sourceLayerId: String? = null,
    state: String? = null,
  ) =
    QueriedFeature(
      feature = json.encodeToByteArray(),
      sourceId = sourceId,
      sourceLayerId = sourceLayerId,
      state = state?.encodeToByteArray(),
    )
}
