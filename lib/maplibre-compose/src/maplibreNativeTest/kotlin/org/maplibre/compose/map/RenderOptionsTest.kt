package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderOptionsTest {

  @Test
  fun the_default_load_color_is_transparent() {
    assertEquals(Color.Transparent, RenderOptions.Standard.foregroundLoadColor)
  }
}
