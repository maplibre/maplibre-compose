package org.maplibre.compose.expr.compiler

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConstableTypesTest {
  @Test
  fun accepts_types_expr_emit_can_freeze() {
    for (name in
      listOf(
        "kotlin.Boolean",
        "kotlin.Int",
        "kotlin.Double",
        "kotlin.String",
        "kotlin.time.Duration",
        "androidx.compose.ui.graphics.Color",
        "androidx.compose.ui.unit.Dp",
        "androidx.compose.ui.geometry.Offset",
        "org.maplibre.compose.expressions.ast.Expression",
        "org.maplibre.spatialk.geojson.Geometry",
        "org.maplibre.spatialk.geojson.GeoJsonObject",
      )) {
      assertTrue(ConstableTypes.isConstableFqName(name), name)
    }
  }

  @Test
  fun rejects_types_that_cannot_become_style_json() {
    for (name in
      listOf(
        "kotlin.Function1",
        "java.util.HashMap",
        "org.maplibre.compose.map.MapState",
        "kotlin.collections.Iterator",
      )) {
      assertFalse(ConstableTypes.isConstableFqName(name), name)
    }
  }

  @Test
  fun treats_list_and_map_as_container_shapes() {
    assertTrue(ConstableTypes.isListLikeFqName("kotlin.collections.List"))
    assertTrue(ConstableTypes.isMapLikeFqName("kotlin.collections.Map"))
    assertFalse(ConstableTypes.isListLikeFqName("kotlin.String"))
  }
}
