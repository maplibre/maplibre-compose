package org.maplibre.compose.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * The state a map keeps when its surface goes away and comes back: only the render session belongs
 * to the host, so the map, its style and its camera must survive. Uses a real GPU because the half
 * under test is native.
 */
class MlnFfiSurfaceLossTest {

  @Test
  fun a_map_whose_surface_is_lost_and_restored_renders_again_with_its_style_and_camera_intact() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(STYLE)
      it.core.setCameraPosition(CAMERA)
      it.pumpUntilRendered()
      it.pumpUntil("the map to reach its camera") {
        abs(it.core.getCameraPosition().zoom - CAMERA.zoom) < TOLERANCE
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
      val camera = it.core.getCameraPosition()
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

    val CAMERA = CameraPosition(target = Position(longitude = 11.0, latitude = 47.0), zoom = 6.0)

    /** Camera round trips lose a little precision through the projection. */
    const val TOLERANCE = 1e-3

    fun assertNear(expected: Double, actual: Double, message: String) {
      assertTrue(abs(expected - actual) < TOLERANCE, "$message (expected $expected, got $actual)")
    }
  }
}
