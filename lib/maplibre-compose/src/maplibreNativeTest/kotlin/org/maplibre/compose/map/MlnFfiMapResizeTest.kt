package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.MlnFfiMapPresentationAnchor
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.runMapTest

/**
 * A resize must retarget the live session (maplibre-native-ffi #485) rather than re-attach, which
 * would discard the renderer's tile pyramid, atlases, and placement on every frame of a drag.
 */
class MlnFfiMapResizeTest {

  @Test
  fun a_resize_back_and_forth_keeps_reusing_the_one_session() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Json(EmptyStyle))
      fixture.awaitRendered()
      val attaches = fixture.attachCount

      // Every step is a new host target: a borrowed texture cannot be resized, only reallocated.
      listOf(WiderExtent, TallerExtent, BridgeMapFixture.DefaultExtent).forEach { extent ->
        val rendered = fixture.renderedFrames
        fixture.extent = extent
        fixture.awaitUntil("the map to render at ${extent.width}x${extent.height}") {
          fixture.renderedFrames > rendered
        }
      }

      assertEquals(attaches, fixture.attachCount, "no resize should have re-attached")
      assertTrue(
        fixture.session.presentation.retargetCount >= 3,
        "each of the three sizes should have retargeted, got ${fixture.session.presentation.retargetCount}",
      )
    }
  }

  @Test
  fun camera_padding_defines_the_physical_presentation_anchor() = runMapTest {
    BridgeMapFixture.create(BridgeMapFixture.RetinaExtent).use { fixture ->
      fixture.loadStyle(BaseStyle.Json(EmptyStyle))
      fixture.awaitRendered()

      fixture.session.setViewportInsets(
        PaddingValues(start = 120.dp, top = 40.dp, end = 20.dp, bottom = 8.dp),
      )

      val expected = MlnFfiMapPresentationAnchor(x = 612, y = 544)
      fixture.awaitUntil("the padded camera anchor to reach the renderer") {
        fixture.session.presentationAnchor(BridgeMapFixture.RetinaExtent) == expected
      }
      assertEquals(expected, fixture.session.presentationAnchor(BridgeMapFixture.RetinaExtent))
    }
  }

  private companion object {
    /** No sources or layers, so nothing here is waiting on the network. */
    const val EmptyStyle: String = """{"version":8,"sources":{},"layers":[],"name":"resize-test"}"""

    val WiderExtent: MapExtent = MapExtent.fromLogical(width = 640, height = 512, scaleFactor = 1.0)

    val TallerExtent: MapExtent =
      MapExtent.fromLogical(width = 640, height = 600, scaleFactor = 1.0)
  }
}
