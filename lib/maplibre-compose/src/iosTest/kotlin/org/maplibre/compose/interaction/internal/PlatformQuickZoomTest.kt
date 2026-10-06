package org.maplibre.compose.interaction.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.interaction.QuickZoomDirection
import org.maplibre.compose.map.MapUiOptions

class PlatformQuickZoomTest {
  @Test
  fun quick_zoom_follows_apple_maps_unless_the_app_chooses() {
    assertEquals(QuickZoomDirection.UpZoomsIn, MapUiOptions.Standard.bindings.tapDrag.direction)
    val googleStyle = MapUiOptions {
      bindings { tapDrag { direction = QuickZoomDirection.DownZoomsIn } }
    }
    assertEquals(QuickZoomDirection.DownZoomsIn, googleStyle.bindings.tapDrag.direction)
  }
}
