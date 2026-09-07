package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
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
