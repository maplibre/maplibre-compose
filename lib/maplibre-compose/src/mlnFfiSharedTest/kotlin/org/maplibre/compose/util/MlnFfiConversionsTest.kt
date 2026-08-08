package org.maplibre.compose.util

import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.nativeffi.geo.Feature
import org.maplibre.nativeffi.geo.FeatureIdentifier
import org.maplibre.nativeffi.geo.Geometry
import org.maplibre.nativeffi.json.JsonValue
import org.maplibre.spatialk.geojson.Feature as GeoJsonFeature
import org.maplibre.spatialk.geojson.GeometryCollection

class MlnFfiConversionsTest {

  @Test
  fun unsigned_feature_id_remains_a_json_number() {
    val feature =
      Feature(
        geometry = Geometry.Empty,
        properties = emptyList(),
        identifier = FeatureIdentifier.UInt(Long.MIN_VALUE),
      )

    val id = feature.toGeoJsonFeature().id as JsonPrimitive

    assertFalse(id.isString)
    assertEquals("9223372036854775808", id.content)
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

    val converted = requireNotNull(feature.toFfiClusterFeature())
    val properties = converted.properties.associate { it.key to it.value }

    assertEquals(42L, assertIs<JsonValue.UInt>(properties["cluster_id"]).value)
    assertEquals("caller-source", assertIs<JsonValue.StringValue>(properties["\$source"]).value)
    assertEquals("caller-state", assertIs<JsonValue.StringValue>(properties["\$state"]).value)
    assertEquals("cluster", assertIs<JsonValue.StringValue>(properties["name"]).value)
  }
}
