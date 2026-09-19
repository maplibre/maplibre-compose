package org.maplibre.compose.sources

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.style.uninstall
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

class CustomGeometrySourceRenderingTest {

  private var featureName: String = FIRST_NAME

  @Test
  fun a_custom_geometry_source_renders_a_polygon_with_the_default_source_layer(): MapTestResult =
    runMapTest {
      val requests = RecordingList<TileCoordinate>()
      createMapFixture().use { fixture ->
        fixture.loadStyle(BLACK_STYLE)
        val style = assertNotNull(fixture.style)
        val source =
          CustomGeometrySource(SOURCE_ID, CustomGeometrySourceOptions(minZoom = 0, maxZoom = 0)) {
            tile ->
            requests += tile
            cover(tile.bounds)
          }
        fixture.state.style.sources.add(source)
        val layer = FillLayer("custom-geometry-fill", source)
        layer.setFillColor(const(Color.Blue).compile(ExpressionContext.None))
        style.install(layer)

        fixture.pumpUntilPixel("the custom geometry polygon to render", CENTER, CENTER, BLUE)

        assertTrue(requests.isNotEmpty())
        assertEquals(TileCoordinate(zoomLevel = 0, x = 0, y = 0), requests.first())
      }
    }

  @Test
  fun a_custom_geometry_layer_ignores_a_declared_source_layer(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      val style = assertNotNull(fixture.style)
      val source =
        CustomGeometrySource(SOURCE_ID, CustomGeometrySourceOptions(minZoom = 0, maxZoom = 0)) {
          cover(it.bounds)
        }
      fixture.state.style.sources.add(source)
      val layer = FillLayer("custom-geometry-fill", source)
      layer.sourceLayer = "ignored"
      layer.setFillColor(const(Color.Blue).compile(ExpressionContext.None))
      style.install(layer)

      fixture.pumpUntilPixel(
        "the polygon with an ignored source layer to render",
        CENTER,
        CENTER,
        BLUE,
      )
    }
  }

  @Test
  fun invalidating_a_tile_requests_the_provider_again_and_renders_its_new_features():
    MapTestResult = runMapTest {
    val requests = RecordingList<TileCoordinate>()
    createMapFixture().use { fixture ->
      featureName = FIRST_NAME
      val handle = fixture.attachInvalidatingSource(requests)
      val answered = requests.size
      featureName = SECOND_NAME

      handle.invalidateTile(TileCoordinate(zoomLevel = 0, x = 0, y = 0))

      fixture.pumpUntil("the invalidated tile to be requested again") {
        requests.size > answered
      }
      fixture.pumpUntilPixel("the provider's new features to render", CENTER, CENTER, RED)
    }
  }

  @Test
  fun invalidating_bounds_requests_the_provider_again_and_renders_its_new_features():
    MapTestResult = runMapTest {
    val requests = RecordingList<TileCoordinate>()
    createMapFixture().use { fixture ->
      featureName = FIRST_NAME
      val handle = fixture.attachInvalidatingSource(requests)
      val answered = requests.size
      featureName = SECOND_NAME

      handle.invalidateBounds(
        BoundingBox(southwest = Position(-10.0, -10.0), northeast = Position(10.0, 10.0))
      )

      fixture.pumpUntil("the invalidated bounds to be requested again") {
        requests.size > answered
      }
      fixture.pumpUntilPixel("the provider's new features to render", CENTER, CENTER, RED)
    }
  }

  /** Adds a source whose fill color follows the `name` property of the provider's features. */
  private suspend fun MapFixture.attachInvalidatingSource(
    requests: RecordingList<TileCoordinate>
  ): CustomGeometrySourceHandle {
    loadStyle(BLACK_STYLE)
    val source =
      CustomGeometrySource(
        SOURCE_ID,
        CustomGeometrySourceOptions(minZoom = 0, maxZoom = 0),
      ) { tile ->
        requests += tile
        cover(tile.bounds, featureName)
      }
    val handle = assertIs<CustomGeometrySourceHandle>(state.style.sources.add(source))
    val layer = FillLayer("custom-geometry-fill", source)
    layer.setFillColor(
      switch(
          condition(test = feature["name"] eq const(SECOND_NAME), output = const(Color.Red)),
          fallback = const(Color.Blue),
        )
        .compile(ExpressionContext.None)
    )
    assertNotNull(style).install(layer)
    pumpUntilPixel("the provider's first features to render", CENTER, CENTER, BLUE)
    return handle
  }

  @Test
  fun replacing_the_style_cancels_a_geometry_provider_call(): MapTestResult = runMapTest {
    val state = CancellationState()
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      val style = assertNotNull(fixture.style)
      val source =
        CustomGeometrySource(SOURCE_ID, CustomGeometrySourceOptions(minZoom = 0, maxZoom = 0)) {
          state.started = true
          try {
            awaitCancellation()
          } finally {
            state.cancelled = true
          }
        }
      style.install(source)
      style.install(FillLayer("custom-geometry-fill", source))
      fixture.pumpUntil("the custom geometry provider to start") { state.started }

      fixture.loadStyle(REPLACEMENT_STYLE)

      fixture.pumpUntil("the detached custom geometry provider to be cancelled") {
        state.cancelled
      }
    }
  }

  @Test
  fun removing_the_source_cancels_a_geometry_provider_call(): MapTestResult = runMapTest {
    val state = CancellationState()
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      val style = assertNotNull(fixture.style)
      val source =
        CustomGeometrySource(SOURCE_ID, CustomGeometrySourceOptions(minZoom = 0, maxZoom = 0)) {
          state.started = true
          try {
            awaitCancellation()
          } finally {
            state.cancelled = true
          }
        }
      val layer = FillLayer("custom-geometry-fill", source)
      style.install(source)
      style.install(layer)
      fixture.pumpUntil("the custom geometry provider to start") { state.started }

      style.uninstall(layer)
      style.uninstall(source)

      fixture.pumpUntil("the removed custom geometry provider to be cancelled") { state.cancelled }
    }
  }

  private class CancellationState {
    var started = false
    var cancelled = false
  }

  private companion object {
    const val SOURCE_ID = "custom-geometry"
    const val CENTER = 256
    const val FIRST_NAME = "first"
    const val SECOND_NAME = "second"
    val BLUE = RgbaPixel(red = 0, green = 0, blue = 255, alpha = 255)
    val RED = RgbaPixel(red = 255, green = 0, blue = 0, alpha = 255)

    val BLACK_STYLE =
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

    val REPLACEMENT_STYLE =
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

    /** One polygon covering the whole tile, mirroring what a provider would return for it. */
    fun cover(bounds: BoundingBox, name: String? = null): FeatureCollection<*, *> {
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
      val properties =
        if (name == null) JsonObject(emptyMap()) else buildJsonObject { put("name", name) }
      return FeatureCollection(
        listOf(Feature(geometry = Polygon(listOf(ring)), properties = properties))
      )
    }
  }
}
