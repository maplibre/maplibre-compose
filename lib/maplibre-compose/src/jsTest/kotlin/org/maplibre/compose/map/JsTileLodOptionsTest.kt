package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class JsTileLodOptionsTest {
  @Test
  fun presets_retain_their_tile_selection_parameters() {
    assertEquals(9.314, TileLodOptions.Standard.maxZoomLevelsOnScreen)
    assertEquals(3.0, TileLodOptions.Standard.tileCountMaxMinRatio)
    assertEquals(11.0, TileLodOptions.Performance.maxZoomLevelsOnScreen)
    assertEquals(1.5, TileLodOptions.Performance.tileCountMaxMinRatio)
    assertEquals(4.0, TileLodOptions.HighDetail.maxZoomLevelsOnScreen)
    assertEquals(8.0, TileLodOptions.HighDetail.tileCountMaxMinRatio)
  }

  @Test
  fun edits_inherit_settings_and_retained_builders_cannot_mutate_values() {
    lateinit var builder: TileLodOptions.Builder
    val original =
      TileLodOptions(TileLodOptions.Performance) {
        builder = this
        maxZoomLevelsOnScreen = 7.0
      }
    builder.maxZoomLevelsOnScreen = 12.0
    builder.tileCountMaxMinRatio = 10.0
    assertEquals(7.0, original.maxZoomLevelsOnScreen)
    assertEquals(1.5, original.tileCountMaxMinRatio)

    val edited = TileLodOptions(original) { tileCountMaxMinRatio = 4.0 }
    assertEquals(7.0, edited.maxZoomLevelsOnScreen)
    assertEquals(4.0, edited.tileCountMaxMinRatio)
    val equal = TileLodOptions {
      maxZoomLevelsOnScreen = 7.0
      tileCountMaxMinRatio = 4.0
    }
    assertEquals(edited, equal)
    assertEquals(edited.hashCode(), equal.hashCode())
    assertEquals(edited, TileLodOptions(edited) {})
    assertNotEquals(original, edited)
    assertNotEquals(edited, TileLodOptions(edited) { maxZoomLevelsOnScreen = 8.0 })
    assertEquals(TileLodOptions.Standard, TileLodOptions {})
  }
}
