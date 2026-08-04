package org.maplibre.compose.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.desktop.HeadlessMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * What a map keeps when its surface goes away and comes back.
 *
 * The division the desktop implementation is built on is that the runtime, the map, its style and
 * its camera live on the map's own thread, and only the render session belongs to the host's. That
 * is what makes losing a surface survivable at all — but nothing proved it, because until now
 * nothing ever drove `onSurfaceLost` and got a surface back afterwards.
 *
 * A real GPU rather than the fake host, because the interesting half is native: the session has to
 * close, the map has to accept a second attach, and the style and camera have to still be there
 * when it does. None of that is exercised by a host that hands out invented handles.
 */
class DesktopSurfaceLossTest {

  @Test
  fun `a map whose surface is lost and restored renders again with its style and camera intact`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(STYLE)
      it.session.setCameraPosition(CAMERA)
      it.pumpUntilRendered()
      it.pumpUntil("the map to reach its camera") {
        abs(it.session.getCameraPosition().zoom - CAMERA.zoom) < TOLERANCE
      }
      val attachesBefore = it.attachCount
      val styleLoadsBefore = it.events.count { event -> event == HeadlessMapFixture.STYLE_LOADED }

      it.loseSurface()
      it.restoreSurface()

      // The whole point: the map is idle, so nothing but the restore itself will ever ask for the
      // frame that re-attaches. Before this worked, the fixture would spin here until it timed out.
      it.pumpUntilRendered()

      assertEquals(
        attachesBefore + 1,
        it.attachCount,
        "the restored surface should have attached exactly one new render session",
      )
      assertEquals(
        styleLoadsBefore,
        it.events.count { event -> event == HeadlessMapFixture.STYLE_LOADED },
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
   * Losing a surface that never comes back, and then closing.
   *
   * Teardown is a handshake — the session goes first, on the thread that attached it, and only then
   * can the map be destroyed — so a map that already gave its session up has to close without
   * closing anything twice or waiting for a thread that is gone.
   */
  @Test
  fun `a map whose surface is lost closes cleanly`() {
    val fixture = HeadlessMapFixture.create()
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
