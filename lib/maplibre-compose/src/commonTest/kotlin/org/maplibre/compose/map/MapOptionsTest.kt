package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.internal.InteractionBindings

class MapOptionsTest {
  @Test
  fun options_inherit_omitted_settings_and_compare_by_value() {
    val capped = RenderOptions { maximumFps = 30 }
    val debugCapped = RenderOptions(capped) { debug { tileBorders = true } }
    assertEquals(30, debugCapped.maximumFps)
    assertTrue(debugCapped.debug.tileBorders)
    assertFalse(debugCapped.debug.collisionBoxes)
    assertEquals(
      RenderOptions(RenderOptions.Debug) { maximumFps = 30 },
      RenderOptions(capped) {
        debug {
          tileBorders = true
          collisionBoxes = true
        }
      },
    )
    assertNotEquals(RenderOptions.Standard, capped)
    assertEquals(
      TileLodOptions.Performance,
      RenderOptions { tileLod = TileLodOptions.Performance }.tileLod,
    )
    assertEquals(TileLodOptions.Standard, TileLodOptions {})
    assertFailsWith<IllegalArgumentException> { RenderOptions { maximumFps = 0 } }

    val red = MapUiOptions { loadColor = Color.Red }
    assertEquals(Color.Red, MapUiOptions(red) { bindings { scroll { enabled = false } } }.loadColor)
    assertEquals(InteractionBindings.standard(), red.bindings)
    assertEquals(InteractionBindings.none(), MapUiOptions.None.bindings)
    assertNotEquals(MapUiOptions.Standard, red)
  }

  @Test
  fun none_interactions_disallow_every_camera_movement() {
    val settings = MapInteractions.None.camera.settings
    assertFalse(
      settings.pan.enabled ||
        settings.zoom.enabled ||
        settings.rotate.enabled ||
        settings.tilt.enabled
    )
    val panOnly = MapInteractions(MapInteractions.None) { camera { pan { enabled = true } } }
    assertTrue(panOnly.camera.settings.pan.enabled)
    assertFalse(panOnly.camera.settings.zoom.enabled)
  }
}
