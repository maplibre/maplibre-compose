package org.maplibre.compose.util

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.nativeffi.EdgeInsets
import org.maplibre.nativeffi.RenderedFeature
import org.maplibre.spatialk.geojson.Feature as GeoJsonFeature
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

class MlnFfiConversionsTest {

  @Test
  fun relative_padding_clamps_floating_point_residue_at_zero() {
    // A sheet inset of 188.66666666666666 dp round-trips through the engine as a slightly smaller
    // double; the difference must not become a negative padding that the engine rejects.
    val engine = EdgeInsets(top = 0.0, left = 0.0, bottom = 188.66666666666663, right = 0.0)
    val insets = EdgeInsets(top = 0.0, left = 0.0, bottom = 188.66666666666666, right = 0.0)
    assertEquals(DpPadding.Zero, engine.relativeTo(insets))
    assertEquals(
      DpPadding(bottom = 20.dp),
      EdgeInsets(top = 0.0, left = 0.0, bottom = 40.0, right = 0.0)
        .relativeTo(EdgeInsets(top = 0.0, left = 0.0, bottom = 20.0, right = 0.0)),
    )
  }

  @Test
  fun unsigned_feature_id_remains_a_json_number() {
    val feature =
      queriedFeature(
          """{"type":"Feature","id":9223372036854775808,"geometry":{"type":"Point","coordinates":[0.0,0.0]},"properties":{}}""",
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
  fun a_cluster_id_reads_from_any_numeric_shape() {
    fun clusterId(value: JsonPrimitive) =
      GeoJsonFeature(
          geometry = GeometryCollection(emptyList()),
          properties = buildJsonObject { put("cluster_id", value) },
          id = null,
        )
        .clusterIdOrNull()

    assertEquals(42u, clusterId(JsonPrimitive(42)))
    assertEquals(42u, clusterId(JsonPrimitive("42")))
    assertEquals(42u, clusterId(JsonPrimitive(42.0)))
    assertNull(clusterId(JsonPrimitive(-1)))
    assertNull(clusterId(JsonPrimitive(1.5)))
    assertNull(clusterId(JsonPrimitive("cluster")))
  }

  private fun queriedFeature(
    json: String,
    sourceId: String? = null,
    sourceLayerId: String? = null,
    state: String? = null,
  ) =
    RenderedFeature(
      feature = json,
      sourceId = sourceId.orEmpty(),
      sourceLayerId = sourceLayerId.orEmpty(),
      state = state.orEmpty(),
      layerId = "layer",
    )
}
