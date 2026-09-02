package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle

class MlnFfiRenderOptionsTest {

  @Test
  fun camera_projection_reaches_the_map_and_returns_to_perspective() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.session.setRenderSettings(
        RenderOptions(cameraProjection = CameraProjection.Axonometric(xSkew = 0.25, ySkew = 0.5))
      )

      val axonometric = assertNotNull(fixture.session.readMap { it.projectionMode })
      assertTrue(assertNotNull(axonometric.axonometric))
      assertEquals(0.25, axonometric.xSkew)
      assertEquals(0.5, axonometric.ySkew)

      fixture.session.setRenderSettings(RenderOptions.Standard)

      val perspective = assertNotNull(fixture.session.readMap { it.projectionMode })
      assertFalse(assertNotNull(perspective.axonometric))
    }
  }
}
