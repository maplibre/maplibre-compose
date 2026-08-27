package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SessionOptionsPaddingTest {

  private fun options(padding: PaddingValues.Absolute) =
    SessionOptions(
      cameraPadding = padding,
      zoomRange = 0f..20f,
      pitchRange = 0f..60f,
      boundingBox = null,
      options = MapOptions(),
    )

  @Test
  fun a_layout_direction_flip_changes_the_captured_options_for_directional_padding() {
    val directional = PaddingValues(start = 8.dp)
    assertNotEquals(
      options(directional.resolveAbsolute(LayoutDirection.Ltr)),
      options(directional.resolveAbsolute(LayoutDirection.Rtl)),
      "directional padding must reapply when the layout direction flips",
    )
  }

  @Test
  fun symmetric_padding_stays_equal_across_a_direction_flip() {
    val symmetric = PaddingValues(horizontal = 8.dp)
    assertEquals(
      options(symmetric.resolveAbsolute(LayoutDirection.Ltr)),
      options(symmetric.resolveAbsolute(LayoutDirection.Rtl)),
      "a flip with no directional padding must not reapply the options",
    )
  }
}
