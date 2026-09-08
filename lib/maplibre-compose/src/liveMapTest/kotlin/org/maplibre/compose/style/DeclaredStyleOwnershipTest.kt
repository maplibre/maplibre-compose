package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.ImageSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.sources.rememberImageSource
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.declare
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.PositionQuad
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

class DeclaredStyleOwnershipTest {
  @Test
  fun declared_sources_allow_runtime_operations_without_mutation_capabilities(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        lateinit var source: GeoJsonSource
        fixture.declare {
          source = rememberGeoJsonSource(DATA, GeoJsonOptions(cluster = true))
          CircleLayer("points", source, visible = true)
        }
        val handle = assertNotNull(fixture.state.style.sources[source])
        val selected = buildJsonObject { put("selected", true) }
        handle.setFeatureState("0", selected)
        assertEquals(selected, handle.getFeatureState("0"))
        handle.removeFeatureState("0", "selected")
        assertEquals(JsonObject(emptyMap()), handle.getFeatureState("0"))
        handle.setFeatureState("0", selected)
        handle.resetFeatureStates()
        assertEquals(JsonObject(emptyMap()), handle.getFeatureState("0"))
        assertNull(handle.asMutable)
        val layer = assertNotNull(fixture.state.style.layers["points"])
        assertEquals(JsonPrimitive("circle"), layer.getProperty("type"))
        assertNull(layer.asMutable)

        val area = DpRect(0.dp, 0.dp, 512.dp, 512.dp)
        fixture.pumpUntil("the declared source's cluster") {
          fixture.state.queryRenderedFeatures(area).any(handle::isCluster)
        }
        val cluster = fixture.state.queryRenderedFeatures(area).first(handle::isCluster)
        assertTrue(handle.getClusterExpansionZoom(cluster) > 0.0)
        assertTrue(handle.getClusterChildren(cluster).features.isNotEmpty())
        assertEquals(3, handle.getClusterLeaves(cluster, 10, 0).features.size)

        fixture.loadStyle(BaseStyle.Empty)
        assertFailsWith<IllegalStateException> { handle.getFeatureState("0") }
      }
    }

  @Test
  fun declared_image_sources_have_no_mutation_capability(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val bounds =
        PositionQuad(
          Position(-1.0, 1.0),
          Position(1.0, 1.0),
          Position(1.0, -1.0),
          Position(-1.0, -1.0),
        )
      val bitmap = ImageBitmap(1, 1)
      lateinit var source: ImageSource
      fixture.declare {
        source = rememberImageSource(bounds, bitmap)
        RasterLayer("image", source, visible = true)
      }
      val handle = assertNotNull(fixture.state.style.sources[source])
      assertNull(handle.asMutable)
    }
  }

  private companion object {
    val DATA =
      GeoJsonData.Features(
        buildFeatureCollection<Geometry, JsonObject?> {
          repeat(3) { index ->
            addFeature(geometry = Point(Position(index * 0.001, 0.0))) { setId(index.toLong()) }
          }
        }
      )
  }
}
