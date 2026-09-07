package org.maplibre.compose.style

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.map.DefaultStyleCompositionEvaluator
import org.maplibre.compose.map.SnapshotStyleOwnership
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.ImageSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.sources.rememberImageSource
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.compose.util.PositionQuad
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

class DeclaredStyleOwnershipTest {
  @Test
  fun declared_sources_allow_feature_state_and_clusters_but_reject_definition_writes():
    MapTestResult = runMapTest {
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
      assertFailsWith<StyleHandleException> { handle.setData(EMPTY_DATA) }
      assertFailsWith<StyleHandleException> { fixture.state.style.sources.remove(handle.id) }

      val layer = assertNotNull(fixture.state.style.layers["points"])
      assertEquals(JsonPrimitive("circle"), layer.getProperty("type"))
      assertFailsWith<StyleHandleException> {
        layer.setPaintProperty("circle-radius", JsonPrimitive(20))
      }
      assertFailsWith<StyleHandleException> {
        layer.setLayoutProperty("visibility", JsonPrimitive("none"))
      }
      assertFailsWith<StyleHandleException> { layer.setRootProperty("minzoom", JsonPrimitive(2)) }
      assertFailsWith<StyleHandleException> { layer.clearFilter() }
      assertFailsWith<StyleHandleException> { layer.setPaintTransition("circle-radius", null) }

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
  fun declared_image_sources_reject_each_definition_write(): MapTestResult = runMapTest {
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
      assertFailsWith<StyleHandleException> { handle.setBounds(bounds) }
      assertFailsWith<StyleHandleException> { handle.setImage(bitmap) }
      assertFailsWith<StyleHandleException> { handle.setUri("https://example.invalid/image.png") }
    }
  }

  /** Evaluate real composables, then publish and reconcile through the map's production paths. */
  private suspend fun MapFixture.declare(content: @Composable @MaplibreComposable () -> Unit) {
    awaitMapReady()
    val revision =
      DefaultStyleCompositionEvaluator.evaluate(
        content,
        checkNotNull(style),
        checkNotNull(session.getViewport()),
        Density(1f),
        LayoutDirection.Ltr,
        SnapshotStyleOwnership.Empty,
      )
    state.beginStyleRevision(session, revision)
    session.reconcileStyleRevision(revision)
    assertTrue(state.markStyleReady(session))
  }

  private companion object {
    val EMPTY_DATA = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}""")
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
