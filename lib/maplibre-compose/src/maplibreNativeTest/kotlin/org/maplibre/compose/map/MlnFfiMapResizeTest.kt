package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.MlnFfiMapPresentationAnchor
import org.maplibre.compose.style.BaseStyle

/**
 * A resize must retarget the live session (maplibre-native-ffi #485) rather than re-attach, which
 * would discard the renderer's tile pyramid, atlases, and placement on every frame of a drag.
 */
class MlnFfiMapResizeTest {

  @Test
  fun a_resize_back_and_forth_keeps_reusing_the_one_session() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      fixture.loadStyle(BaseStyle.Json(EMPTY_STYLE))
      fixture.pumpUntilRendered()
      val attaches = fixture.session.attachCount

      // Every step is a new host target: a borrowed texture cannot be resized, only reallocated.
      listOf(WIDER_EXTENT, TALLER_EXTENT, BridgeMapFixture.DEFAULT_EXTENT).forEach { extent ->
        fixture.hasRendered = false
        fixture.pumpUntil("the resized map to render", extent = extent) { fixture.hasRendered }
      }

      assertEquals(attaches, fixture.session.attachCount, "no resize should have re-attached")
      assertTrue(
        fixture.session.retargetCount >= 3,
        "each of the three sizes should have retargeted, got ${fixture.session.retargetCount}",
      )
    }
  }

  @Test
  fun camera_padding_defines_the_physical_presentation_anchor() {
    BridgeMapFixture.create(BridgeMapFixture.RETINA_EXTENT).use { fixture ->
      fixture.loadStyle(BaseStyle.Json(EMPTY_STYLE), extent = BridgeMapFixture.RETINA_EXTENT)
      fixture.pumpUntilRendered(BridgeMapFixture.RETINA_EXTENT)

      fixture.session.setCameraPadding(
        PaddingValues(start = 120.dp, top = 40.dp, end = 20.dp, bottom = 8.dp)
      )

      val expected = MlnFfiMapPresentationAnchor(x = 612, y = 544)
      fixture.pumpUntil(
        "the padded camera anchor to reach the renderer",
        extent = BridgeMapFixture.RETINA_EXTENT,
      ) {
        fixture.session.presentationAnchor(BridgeMapFixture.RETINA_EXTENT) == expected
      }
      assertEquals(expected, fixture.session.presentationAnchor(BridgeMapFixture.RETINA_EXTENT))
    }
  }

  private companion object {
    /** No sources or layers, so nothing here is waiting on the network. */
    const val EMPTY_STYLE: String =
      """{"version":8,"sources":{},"layers":[],"name":"resize-test"}"""

    val WIDER_EXTENT: MapExtent =
      MapExtent.fromLogical(width = 640, height = 512, scaleFactor = 1.0)

    val TALLER_EXTENT: MapExtent =
      MapExtent.fromLogical(width = 640, height = 600, scaleFactor = 1.0)
  }
}
