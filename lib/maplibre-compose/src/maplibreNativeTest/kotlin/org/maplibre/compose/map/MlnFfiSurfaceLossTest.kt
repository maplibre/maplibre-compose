package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.all
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/**
 * What a map keeps when its surface goes away and comes back: only the render session belongs to
 * the host, so the map, its style and its camera must survive. Uses a real GPU because the half
 * under test is native.
 */
class MlnFfiSurfaceLossTest {

  @Test
  fun a_map_whose_surface_is_lost_and_restored_renders_again_with_its_style_and_camera_intact() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(STYLE)
      it.session.setCameraPosition(CAMERA)
      it.pumpUntilRendered()
      it.pumpUntil("the map to reach its camera") {
        abs(it.session.getCameraPosition().zoom - CAMERA.zoom) < TOLERANCE
      }
      val attachesBefore = it.attachCount
      val styleLoadsBefore = it.events.count { event -> event == BridgeMapFixture.STYLE_LOADED }

      it.loseSurface()
      it.restoreSurface()

      // The map is idle, so nothing but the restore itself will ask for the re-attaching frame.
      it.pumpUntilRendered()

      assertEquals(
        attachesBefore + 1,
        it.attachCount,
        "the restored surface should have attached exactly one new render session",
      )
      assertEquals(
        styleLoadsBefore,
        it.events.count { event -> event == BridgeMapFixture.STYLE_LOADED },
        "the style lives on the map, so surface loss should not have reloaded it",
      )
      val camera = it.session.getCameraPosition()
      assertNear(CAMERA.zoom, camera.zoom, "zoom should survive surface loss")
      assertNear(
        CAMERA.target.longitude,
        camera.target.longitude,
        "longitude should survive surface loss",
      )
      assertNear(
        CAMERA.target.latitude,
        camera.target.latitude,
        "latitude should survive surface loss",
      )
      assertTrue(
        it.errors.isEmpty(),
        "losing and restoring the surface reported errors: ${it.errors}",
      )
    }
  }

  /**
   * Losing a surface that never comes back, and then closing. Teardown closes the render session on
   * the thread that attached it before destroying the map, so a map that already gave its session
   * up must close without closing twice or waiting on a thread that is gone.
   */
  @Test
  fun a_map_whose_surface_is_lost_closes_cleanly() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(STYLE)
      it.pumpUntilRendered()
      it.loseSurface()
      it.session.close()
      it.session.close()
    }
  }

  @Test
  fun feature_state_accepts_mutations_without_a_surface_and_replays_into_its_replacement() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BLACK_STYLE)
      it.session.setCameraPosition(
        CameraPosition(target = Position(longitude = 0.0, latitude = 0.0), zoom = 1.0)
      )
      val style = checkNotNull(it.style)
      val source =
        GeoJsonSource(
          id = "points",
          data =
            GeoJsonData.Features(
              buildFeatureCollection<Geometry, JsonObject?> {
                addFeature(geometry = Point(Position(0.0, 0.0))) { setId(1) }
              }
            ),
          options = GeoJsonOptions(),
        )
      style.install(source)
      val layer = CircleLayer("circles", source)
      layer.setCircleRadius(const(48.dp).compile(ExpressionContext.None))
      layer.setCircleColor(
        switch(
            condition(
              all(
                feature.state<BooleanValue>("before-surface").asBoolean(const(false)),
                feature.state<BooleanValue>("without-surface").asBoolean(const(false)),
              ),
              const(Color.Red),
            ),
            fallback = const(Color.Blue),
          )
          .compile(ExpressionContext.None)
      )
      style.install(layer)

      style.setFeatureState(source.id, null, "1", state("before-surface"))
      it.pumpUntil("the incomplete feature state to render blue") {
        it.tryReadPixel(CENTER, CENTER)?.isNear(BLUE) == true
      }
      it.loseSurface()
      style.setFeatureState(source.id, null, "1", state("without-surface"))
      assertEquals(
        state("before-surface", "without-surface"),
        style.featureState(source.id, null, "1"),
      )
      it.restoreSurface()
      it.pumpUntil("feature state to replay into the replacement renderer") {
        it.tryReadPixel(CENTER, CENTER)?.isNear(RED) == true
      }

      it.loseSurface()
      style.resetFeatureStates(source.id, null)
      assertEquals(
        JsonObject(emptyMap()),
        style.featureState(source.id, null, "1"),
      )
      it.restoreSurface()
      it.pumpUntil("the reset feature state to replay") {
        it.tryReadPixel(CENTER, CENTER)?.isNear(BLUE) == true
      }
    }
  }

  private companion object {
    /** Inline and layer-only, so the test needs no network to prove a style survived. */
    val STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"bg","type":"background","paint":{"background-color":"#eee"}}
        ]}
        """
      )

    val BLACK_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#000000"}}
        ]}
        """
          .trimIndent()
      )

    val RED = RgbaPixel(red = 255, green = 0, blue = 0, alpha = 255)
    val BLUE = RgbaPixel(red = 0, green = 0, blue = 255, alpha = 255)
    const val CENTER = 256

    val CAMERA = CameraPosition(target = Position(longitude = 11.0, latitude = 47.0), zoom = 6.0)

    /** Camera round trips lose a little precision through the projection. */
    const val TOLERANCE = 1e-3

    fun state(vararg keys: String): JsonObject = buildJsonObject {
      keys.forEach { key -> put(key, true) }
    }

    fun assertNear(expected: Double, actual: Double, message: String) {
      assertTrue(abs(expected - actual) < TOLERANCE, "$message (expected $expected, got $actual)")
    }
  }
}
