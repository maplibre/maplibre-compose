package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.systemAnimatorDurationScale
import org.maplibre.compose.testing.skipMapTest
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.toPosition
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.nativeffi.geo.ScreenPoint
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.ProjectionModeOptions
import org.maplibre.spatialk.geojson.Position

@OptIn(DelicateMapApi::class)
class MlnFfiViewportTest {
  @Test
  fun viewport_insets_do_not_stop_an_unanchored_camera_animation() = runBlocking {
    if (systemAnimatorDurationScale() == 0f) skipMapTest("System animations are disabled")
    BridgeMapFixture.create().use { fixture ->
      val state = fixture.state
      state.publishPresentation(state.reservePresentation(), fixture.session)
      fixture.bindState(state)
      fixture.loadStyle(BaseStyle.Empty)
      val start = CameraPosition(zoom = 3.0, padding = DpPadding(top = 12.dp))
      state.setCameraPosition(start)
      fixture.pumpUntil("the starting camera") {
        fixture.session.getCameraPosition().zoom == start.zoom
      }
      val target = start.copy(zoom = 8.0, bearing = 90.0)
      val animation = async {
        state.animateCamera(target.toCameraUpdate(), CameraAnimation.Ease(2.seconds))
      }
      fixture.awaitUntil("the camera animation to advance") {
        fixture.session.getCameraPosition().zoom > start.zoom + 0.1
      }
      assertFalse(animation.isCompleted, "the inset update must happen during the animation")
      val before = fixture.session.getCameraPosition()
      fixture.session.setViewportInsets(PaddingValues(top = 24.dp))
      fixture.awaitFrames(frames = 2)
      assertFalse(animation.isCompleted, "changing insets must not finish unrelated tracks")
      assertTrue(state.isCameraMoving, "ending the inset command must not report the camera idle")
      fixture.awaitUntil("the camera animation to finish after changing insets") {
        animation.isCompleted
      }
      animation.await()
      assertFalse(state.isCameraMoving, "the final camera completion must report idle")
      val after = fixture.session.getCameraPosition()
      assertTrue(after.zoom > before.zoom, "zoom stopped at the inset update")
      assertTrue(after.bearing > before.bearing, "bearing stopped at the inset update")
      assertEquals(target.zoom, after.zoom, 0.0001)
      assertEquals(target.bearing, after.bearing, 0.0001)
      assertEquals(start.padding, after.padding)
      assertEquals(36.0, fixture.session.readMap { it.camera.padding?.top })
    }
  }

  @Test
  fun padding_changes_before_the_first_frame_preserve_the_requested_camera() {
    checkInitialPadding(tilt = 0.0, cameraAfterPadding = false)
  }

  @Test
  fun a_camera_set_before_the_first_frame_does_not_apply_pending_padding_to_the_bootstrap_size() {
    checkInitialPadding(tilt = 30.0, cameraAfterPadding = true)
  }

  @Test
  fun an_animation_requested_before_the_first_viewport_reaches_its_target() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      val state = fixture.state
      state.publishPresentation(state.reservePresentation(), fixture.session)
      fixture.bindState(state)
      fixture.loadStyleBeforeRendering(BaseStyle.Empty)
      fixture.session.setViewportInsets(PaddingValues(top = 24.dp))
      val target = CameraPosition(target = Position(-74.006, 40.7128), zoom = 5.0)
      val animation =
        async(start = CoroutineStart.UNDISPATCHED) {
          state.animateCamera(target.toCameraUpdate(), CameraAnimation.Fly(200.milliseconds))
        }
      // Let the owner accept the animation before any render target has attached.
      fixture.session.readMap {}
      fixture.awaitUntil("the startup animation to finish") { animation.isCompleted }
      animation.await()
      assertEquals(target.zoom, fixture.session.getCameraPosition().zoom, 0.0001)
    }
  }

  @Test
  fun raw_platform_map_access_that_changes_the_transform_refreshes_the_viewport() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      val state = fixture.state
      state.publishPresentation(state.reservePresentation(), fixture.session)
      fixture.bindState(state)
      fixture.loadStyle(BaseStyle.Empty)
      val camera = CameraPosition(target = Position(-74.006, 40.7128), zoom = 9.0, tilt = 45.0)
      state.setCameraPosition(camera)
      fixture.pumpUntil("the starting camera") {
        fixture.session.getCameraPosition().zoom == camera.zoom
      }
      val before = fixture.session.getVisibleRegion()

      // A projection mode change moves the viewport's corners without a camera event.
      state.withPlatformMap {
        map.projectionMode =
          ProjectionModeOptions().also {
            it.axonometric = true
            it.xSkew = 0.5
            it.ySkew = 0.5
          }
      }
      val engineRegion = fixture.session.readMap { map -> map.visibleRegion() }
      assertNotEquals(before, engineRegion, "the raw access must move the viewport")

      fixture.awaitUntil("the viewport to reflect the raw access", timeout = 10.seconds) {
        fixture.session.getVisibleRegion() == engineRegion
      }
      assertEquals(engineRegion, fixture.session.getViewport()?.visibleRegion)
    }
  }

  private fun MapHandle.visibleRegion(): VisibleRegion {
    val width = size.width.toDouble()
    val height = size.height.toDouble()
    val corners =
      latLngsForPixelsUnwrapped(
          listOf(
            ScreenPoint(0.0, 0.0),
            ScreenPoint(width, 0.0),
            ScreenPoint(0.0, height),
            ScreenPoint(width, height),
          )
        )
        .map { it.toPosition() }
    return VisibleRegion(corners[0], corners[1], corners[2], corners[3])
  }

  private fun checkInitialPadding(tilt: Double, cameraAfterPadding: Boolean) {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyleBeforeRendering(BaseStyle.Empty)
      val camera =
        CameraPosition(
          target = Position(-74.006, 40.7128),
          zoom = 9.5,
          tilt = tilt,
          padding = DpPadding(top = 20.dp),
        )
      if (!cameraAfterPadding) fixture.session.setCameraPosition(camera, null)
      fixture.session.setViewportInsets(PaddingValues(start = 392.dp, top = 28.dp))
      fixture.session.setCameraConstraints(CameraConstraints())
      fixture.session.setViewportInsets(PaddingValues(start = 392.dp, top = 24.dp))
      if (cameraAfterPadding) fixture.session.setCameraPosition(camera, null)
      // Drain configuration while the map still has its bootstrap size, without drawing.
      fixture.session.readMap {}
      val padding = EdgeInsets(top = 44.0, left = 392.0, bottom = 0.0, right = 0.0)
      fixture.pumpUntil("the initial padding to reach the real viewport") {
        fixture.hasRendered && fixture.session.readMap { it.camera.padding } == padding
      }
      assertEquals(tilt, fixture.session.getCameraPosition().tilt, 0.0001)
      assertEquals(camera.padding, fixture.session.getCameraPosition().padding)
    }
  }
}
