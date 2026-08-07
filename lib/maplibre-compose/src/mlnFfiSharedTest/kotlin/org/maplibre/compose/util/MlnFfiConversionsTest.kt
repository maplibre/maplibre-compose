package org.maplibre.compose.util

import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.nativeffi.geo.Feature
import org.maplibre.nativeffi.geo.FeatureIdentifier
import org.maplibre.nativeffi.geo.Geometry

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
}
