package org.maplibre.compose.util

import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.spatialk.geojson.Feature as GeoJsonFeature
import org.maplibre.spatialk.geojson.GeometryCollection

class MlnFfiConversionsTest {

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

    val converted = requireNotNull(feature.toFfiClusterFeature())
    val properties =
      (Json.parseToJsonElement(converted.decodeToString()) as JsonObject)["properties"]!!.jsonObject

    assertEquals("42", properties.getValue("cluster_id").jsonPrimitive.content)
    assertEquals("caller-source", properties.getValue("\$source").jsonPrimitive.content)
    assertEquals("caller-state", properties.getValue("\$state").jsonPrimitive.content)
    assertEquals("cluster", properties.getValue("name").jsonPrimitive.content)
    assertEquals(false, properties.getValue("cluster_id").jsonPrimitive.isString)
  }
}
