package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.spatialk.geojson.Position

class MlnFfiViewportTest {
  @Test
  fun padding_changes_before_the_first_frame_preserve_the_requested_camera() {
    checkInitialPadding(tilt = 0.0, cameraAfterPadding = false)
  }

  @Test
  fun a_camera_set_before_the_first_frame_does_not_apply_pending_padding_to_the_bootstrap_size() {
    checkInitialPadding(tilt = 30.0, cameraAfterPadding = true)
  }

  @Test
  fun viewport_readiness_waits_for_native_size_and_padding() {
    BridgeMapFixture.create().use { fixture ->
      val session = fixture.session
      fixture.loadStyleBeforeRendering(BaseStyle.Empty)
      runBlocking { session.reconcileStyleRevision(DesiredStyleRevision.Empty) }
      session.setCameraPadding(PaddingValues(top = 24.dp))
      withOwnerPaused(session) {
        // Target attachment posts a resize, but the owner cannot apply it yet.
        fixture.frame()
        assertNull(session.getViewport())
        assertFalse(session.isGestureReady)
      }
      fixture.pumpUntil("the configured viewport to become ready") { session.getViewport() != null }
      val viewport = assertNotNull(session.getViewport())
      assertEquals(BridgeMapFixture.DEFAULT_EXTENT.height.dp, viewport.size.height)
      assertEquals(24.0, session.readMap { it.camera.padding?.top })
      assertTrue(session.isGestureReady)
    }
  }

  @Test
  fun a_usable_viewport_remains_available_during_resize_and_surface_loss() {
    BridgeMapFixture.create().use { fixture ->
      val session = fixture.session
      fixture.loadStyleBeforeRendering(BaseStyle.Empty)
      fixture.pumpUntil("the initial viewport") { session.getViewport() != null }
      val viewport = assertNotNull(session.getViewport())
      val resized = MapExtent.fromLogical(640, 480, 1.0)
      withOwnerPaused(session) {
        fixture.frame(resized)
        assertEquals(viewport.size, session.getViewport()?.size)
      }
      fixture.pumpUntil("the resized viewport", extent = resized) {
        session.getViewport()?.size?.height == resized.height.dp
      }
      fixture.loseSurface()
      assertEquals(resized.height.dp, session.getViewport()?.size?.height)
      fixture.restoreSurface()
      fixture.pumpUntil("the restored viewport") { session.getViewport()?.size == viewport.size }
    }
  }

  private fun withOwnerPaused(session: MlnFfiMapSession, action: () -> Unit) {
    val entered = TestLatch(1)
    val release = TestLatch(1)
    assertTrue(
      session.postOwnerTaskForTest {
        entered.countDown()
        check(release.await(5_000))
      }
    )
    try {
      assertTrue(entered.await(5_000))
      action()
    } finally {
      release.countDown()
    }
  }

  private fun checkInitialPadding(tilt: Double, cameraAfterPadding: Boolean) {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyleBeforeRendering(BaseStyle.Empty)
      val camera = CameraPosition(target = Position(-74.006, 40.7128), zoom = 9.5, tilt = tilt)
      if (!cameraAfterPadding) fixture.session.setCameraPosition(camera, null)
      fixture.session.setCameraPadding(PaddingValues(start = 392.dp, top = 28.dp))
      fixture.session.setCameraConstraints(CameraConstraints())
      fixture.session.setCameraPadding(PaddingValues(start = 392.dp, top = 24.dp))
      if (cameraAfterPadding) fixture.session.setCameraPosition(camera, null)
      // Drain configuration while the map still has its bootstrap size, without drawing.
      fixture.session.readMap {}
      val padding = EdgeInsets(top = 24.0, left = 392.0, bottom = 0.0, right = 0.0)
      fixture.pumpUntil("the initial padding to reach the real viewport") {
        fixture.hasRendered && fixture.session.readMap { it.camera.padding } == padding
      }
      assertEquals(tilt, fixture.session.getCameraPosition().tilt, 0.0001)
    }
  }
}
