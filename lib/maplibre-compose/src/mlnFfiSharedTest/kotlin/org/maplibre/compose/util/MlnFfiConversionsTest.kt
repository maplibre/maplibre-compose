package org.maplibre.compose.util

import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.spatialk.geojson.Feature as GeoJsonFeature
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

class MlnFfiConversionsTest {

  @Test
  fun unsigned_feature_id_remains_a_json_number() {
    val result =
      """
      [{"feature":{"type":"Feature","id":9223372036854775808,"geometry":{"type":"Point","coordinates":[0.0,0.0]},"properties":{}}}]
      """

    val id =
      result.trimIndent().encodeToByteArray().toGeoJsonFeatures().single().id as JsonPrimitive

    assertFalse(id.isString)
    assertEquals("9223372036854775808", id.content)
  }

  @Test
  fun query_envelope_drops_query_metadata() {
    val result =
      """
      [{"feature":{"type":"Feature","geometry":{"type":"Point","coordinates":[1.0,2.0]},"properties":{"name":"a"}},"sourceId":"s","sourceLayerId":"l","state":{}}]
      """

    val feature = result.trimIndent().encodeToByteArray().toGeoJsonFeatures().single()

    assertEquals(Point(Position(1.0, 2.0)), feature.geometry)
    assertEquals(buildJsonObject { put("name", "a") }, feature.properties)
  }

  @Test
  fun native_edge_insets_remain_physical_in_rtl() {
    val padding = EdgeInsets(top = 1.0, left = 2.0, bottom = 3.0, right = 4.0).toPaddingValues()

    assertEquals(2f, padding.calculateLeftPadding(LayoutDirection.Rtl).value)
    assertEquals(4f, padding.calculateRightPadding(LayoutDirection.Rtl).value)
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
}
