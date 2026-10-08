package org.maplibre.compose.sources

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.compile
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.style.onOwner
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

class CustomGeometrySourceTest {
  private val requests = RecordingList<TileCoordinate>()

  @Volatile private var featureName = FirstName

  @Test
  fun a_custom_geometry_source_renders_features_a_query_can_hit(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.attachSource()
      fixture.pumpUntil("the answered tile to be queryable") { fixture.queryCenter().isNotEmpty() }

      val feature = fixture.queryCenter().first()
      assertEquals(buildJsonObject { put("name", FirstName) }, feature.properties)
      assertEquals(emptyList(), fixture.errors, "the map should report nothing")
      assertEquals(TileCoordinate(zoomLevel = 0, x = 0, y = 0), requests.first())
    }
  }

  @Test
  fun a_custom_geometry_layer_ignores_a_declared_source_layer(): MapTestResult = runMapTest {
    createMapFixture().use { fixture -> fixture.attachSource(sourceLayer = "ignored") }
  }

  @Test
  fun invalidating_a_tile_requests_the_provider_again_and_renders_its_new_features():
    MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val handle = fixture.attachSource()
      fixture.awaitReload { handle.invalidateTile(TileCoordinate(zoomLevel = 0, x = 0, y = 0)) }
    }
  }

  @Test
  fun invalidating_bounds_requests_the_provider_again_and_renders_its_new_features():
    MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val handle = fixture.attachSource()
      fixture.awaitReload {
        handle.invalidateBounds(
          BoundingBox(southwest = Position(-10.0, -10.0), northeast = Position(10.0, 10.0))
        )
      }
    }
  }

  @Test
  fun replacing_the_style_cancels_a_geometry_provider_call(): MapTestResult = runMapTest {
    val state = CancellationState()
    createMapFixture().use { fixture ->
      fixture.attachPendingSource(state)

      fixture.loadStyle(ReplacementStyle)

      fixture.pumpUntil("the detached custom geometry provider to be cancelled") {
        state.cancelled
      }
    }
  }

  @Test
  fun removing_the_source_cancels_a_geometry_provider_call(): MapTestResult = runMapTest {
    val state = CancellationState()
    createMapFixture().use { fixture ->
      fixture.attachPendingSource(state)
      val style = assertNotNull(fixture.style)

      style.onOwner {
        style.removeLayer(LayerId)
        style.removeSource(SourceId)
      }

      fixture.pumpUntil("the removed custom geometry provider to be cancelled") { state.cancelled }
    }
  }

  /**
   * Adds a source whose fill is blue, or red once the provider names its features [SecondName], and
   * renders its first features.
   */
  private suspend fun MapFixture.attachSource(
    sourceLayer: String? = null
  ): CustomGeometrySourceHandle {
    loadStyle(BlackStyle)
    val source =
      CustomGeometrySource(
        SourceId,
        CustomGeometrySourceOptions {
          minZoom = 0
          maxZoom = 0
        },
      ) { tile ->
        requests += tile
        cover(tile.bounds, featureName)
      }
    val handle = assertIs<CustomGeometrySourceHandle>(state.style.sources.add(source))
    val layer = TestLayer(LayerId, "fill", source)
    sourceLayer?.let { layer.sourceLayer = it }
    layer.paint(
      "fill-color",
      (switch(
            condition(test = feature["name"] eq const(SecondName), output = const(Color.Red)),
            fallback = const(Color.Blue),
          )
          .compile(ExpressionContext.None))
        .asLayerProperty(),
    )
    assertNotNull(style).install(layer)
    pumpUntilPixel("the provider's first features to render", Center, Center, Blue)
    return handle
  }

  /** Invalidates through [invalidate] and waits for the provider's renamed features. */
  private suspend fun MapFixture.awaitReload(invalidate: () -> Unit) {
    val answered = requests.size
    featureName = SecondName

    invalidate()

    pumpUntil("the invalidated tile to be requested again") { requests.size > answered }
    pumpUntilPixel("the provider's new features to render", Center, Center, Red)
    pumpUntil("the provider's new features to be queryable") {
      queryCenter().any { it.properties?.get("name")?.jsonPrimitive?.content == SecondName }
    }
  }

  /** Adds a source whose provider never answers, and waits for the provider to start. */
  private suspend fun MapFixture.attachPendingSource(state: CancellationState) {
    loadStyle(BlackStyle)
    val style = assertNotNull(style)
    val source =
      CustomGeometrySource(
        SourceId,
        CustomGeometrySourceOptions {
          minZoom = 0
          maxZoom = 0
        },
      ) {
        state.started = true
        try {
          awaitCancellation()
        } finally {
          state.cancelled = true
        }
      }
    style.install(source)
    style.install(TestLayer(LayerId, "fill", source))
    pumpUntil("the custom geometry provider to start") { state.started }
  }

  private suspend fun MapFixture.queryCenter() =
    state.queryRenderedFeatures(offset = DpOffset(Center.dp, Center.dp))

  private class CancellationState {
    @Volatile var started = false
    @Volatile var cancelled = false
  }

  private companion object {
    const val SourceId = "custom-geometry"
    const val LayerId = "custom-geometry-fill"
    const val Center = 256
    const val FirstName = "first"
    const val SecondName = "second"
    val Blue = RgbaPixel(red = 0, green = 0, blue = 255, alpha = 255)
    val Red = RgbaPixel(red = 255, green = 0, blue = 0, alpha = 255)

    val BlackStyle =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "name": "custom-geometry-test",
          "sources": {},
          "layers": [
            { "id": "bg", "type": "background", "paint": { "background-color": "#000000" } }
          ]
        }
        """
          .trimIndent()
      )

    val ReplacementStyle =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "name": "custom-geometry-replacement",
          "sources": {},
          "layers": []
        }
        """
          .trimIndent()
      )

    /** One named polygon covering the whole tile. */
    fun cover(bounds: BoundingBox, name: String): FeatureCollection<*, *> {
      val west = bounds.southwest.longitude
      val east = bounds.northeast.longitude
      val south = bounds.southwest.latitude
      val north = bounds.northeast.latitude
      val ring =
        listOf(
          Position(west, south),
          Position(east, south),
          Position(east, north),
          Position(west, north),
          Position(west, south),
        )
      return FeatureCollection(
        listOf(
          Feature(
            geometry = Polygon(listOf(ring)),
            properties = buildJsonObject { put("name", name) },
          )
        )
      )
    }
  }
}
