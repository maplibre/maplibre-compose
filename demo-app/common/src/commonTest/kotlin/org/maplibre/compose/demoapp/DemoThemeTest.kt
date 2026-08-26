package org.maplibre.compose.demoapp

import androidx.compose.material3.lightColorScheme
import com.materialkolor.PaletteStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoThemeTest {
  @Test
  fun palette_modes_map_to_materialkolor_styles() {
    assertNull(PaletteMode.System.toPaletteStyle())
    assertEquals(PaletteStyle.TonalSpot, PaletteMode.Tonal.toPaletteStyle())
    assertEquals(PaletteStyle.Neutral, PaletteMode.Neutral.toPaletteStyle())
    assertEquals(PaletteStyle.Vibrant, PaletteMode.Vibrant.toPaletteStyle())
    assertEquals(PaletteStyle.Expressive, PaletteMode.Expressive.toPaletteStyle())
  }

  @Test
  fun brand_schemes_differ_from_the_stock_material_palette() {
    val tonal = brandColorScheme(dark = false, PaletteStyle.TonalSpot)
    assertNotEquals(lightColorScheme().primary, tonal.primary)
  }

  @Test
  fun brand_schemes_change_with_palette_style() {
    val primaries =
      listOf(
          PaletteStyle.TonalSpot,
          PaletteStyle.Neutral,
          PaletteStyle.Vibrant,
          PaletteStyle.Expressive,
        )
        .map { brandColorScheme(dark = false, it).primary }
        .toSet()
    assertEquals(4, primaries.size)
  }

  @Test
  fun brand_schemes_change_with_light_and_dark() {
    val light = brandColorScheme(dark = false, PaletteStyle.TonalSpot)
    val dark = brandColorScheme(dark = true, PaletteStyle.TonalSpot)
    assertNotEquals(light.primary, dark.primary)
    assertNotEquals(light.surface, dark.surface)
  }

  @Test
  fun default_palette_is_the_first_platform_option() {
    assertEquals(paletteModeOptions.first(), defaultPaletteMode)
    assertTrue(PaletteMode.Tonal in paletteModeOptions)
    assertTrue(PaletteMode.Neutral in paletteModeOptions)
    assertTrue(PaletteMode.Vibrant in paletteModeOptions)
    assertTrue(PaletteMode.Expressive in paletteModeOptions)
    if (PaletteMode.System in paletteModeOptions) {
      assertEquals(PaletteMode.System, defaultPaletteMode)
    } else {
      assertEquals(PaletteMode.Tonal, defaultPaletteMode)
    }
  }
}
