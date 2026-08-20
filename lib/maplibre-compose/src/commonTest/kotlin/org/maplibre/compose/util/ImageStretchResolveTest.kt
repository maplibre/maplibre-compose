package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageStretchResolveTest {

  @Test
  fun cap_insets_become_a_stretch_box_in_image_pixels() {
    assertEquals(
      ImageStretchPixels(
        stretchX = listOf(8f to 24f),
        stretchY = listOf(4f to 28f),
        content = Rect(8f, 4f, 24f, 28f),
      ),
      ImageStretch.capInsets(left = 8.dp, top = 4.dp, right = 8.dp, bottom = 4.dp)
        .resolve(imageWidth = 32, imageHeight = 32, scale = 1f),
    )
  }

  @Test
  fun cap_insets_on_a_retina_image_scale_with_the_bitmap() {
    assertEquals(
      ImageStretchPixels(
        stretchX = listOf(16f to 48f),
        stretchY = listOf(8f to 56f),
        content = Rect(16f, 8f, 48f, 56f),
      ),
      ImageStretch.capInsets(left = 8.dp, top = 4.dp, right = 8.dp, bottom = 4.dp)
        .resolve(imageWidth = 64, imageHeight = 64, scale = 2f),
    )
  }

  @Test
  fun cap_insets_that_leave_nothing_to_stretch_drop_stretch_and_content() {
    assertEquals(
      ImageStretchPixels(),
      ImageStretch.capInsets(left = 20.dp, top = 4.dp, right = 20.dp, bottom = 4.dp)
        .resolve(imageWidth = 32, imageHeight = 32, scale = 1f),
    )
  }

  @Test
  fun cap_insets_can_inset_the_content_box_further_than_the_stretch_border() {
    assertEquals(
      ImageStretchPixels(
        stretchX = listOf(8f to 24f),
        stretchY = listOf(4f to 28f),
        content = Rect(10f, 6f, 22f, 26f),
      ),
      ImageStretch.capInsets(
          stretch = PaddingValues.Absolute(8.dp, 4.dp, 8.dp, 4.dp),
          content = PaddingValues.Absolute(10.dp, 6.dp, 10.dp, 6.dp),
        )
        .resolve(imageWidth = 32, imageHeight = 32, scale = 1f),
    )
  }

  @Test
  fun ranges_keep_stretch_and_content_independent() {
    assertEquals(
      ImageStretchPixels(
        stretchX = listOf(25f to 55f, 85f to 115f),
        stretchY = listOf(25f to 100f),
        content = Rect(25f, 25f, 115f, 100f),
      ),
      ImageStretch(
          x = listOf(12.5.dp..27.5.dp, 42.5.dp..57.5.dp),
          y = listOf(12.5.dp..50.dp),
          content = DpRect(12.5.dp, 12.5.dp, 57.5.dp, 50.dp),
        )
        .resolve(imageWidth = 140, imageHeight = 120, scale = 2f),
    )
  }

  @Test
  fun overlapping_ranges_on_one_axis_drop_that_axis() {
    assertEquals(
      ImageStretchPixels(
        stretchX = emptyList(),
        stretchY = listOf(4f to 28f),
        content = Rect(4f, 4f, 28f, 28f),
      ),
      ImageStretch(
          x = listOf(4.dp..20.dp, 12.dp..28.dp),
          y = listOf(4.dp..28.dp),
          content = DpRect(4.dp, 4.dp, 28.dp, 28.dp),
        )
        .resolve(imageWidth = 32, imageHeight = 32, scale = 1f),
    )
  }

  @Test
  fun empty_ranges_omit_stretch_and_keep_a_content_box() {
    assertEquals(
      ImageStretchPixels(content = Rect(8f, 4f, 24f, 28f)),
      ImageStretch(x = emptyList(), y = emptyList(), content = DpRect(8.dp, 4.dp, 24.dp, 28.dp))
        .resolve(imageWidth = 32, imageHeight = 32, scale = 1f),
    )
  }

  @Test
  fun a_content_box_outside_the_bitmap_is_omitted() {
    assertEquals(
      ImageStretchPixels(
        stretchX = listOf(8f to 24f),
        stretchY = listOf(4f to 28f),
        content = null,
      ),
      ImageStretch(
          x = listOf(8.dp..24.dp),
          y = listOf(4.dp..28.dp),
          content = DpRect(40.dp, 0.dp, 50.dp, 10.dp),
        )
        .resolve(imageWidth = 32, imageHeight = 32, scale = 1f),
    )
  }

  @Test
  fun range_lists_are_copied_so_later_mutation_does_not_change_the_value() {
    val x = mutableListOf(8.dp..24.dp)
    val stretch = ImageStretch(x = x, y = listOf(4.dp..28.dp))
    val hashBefore = stretch.hashCode()

    x.clear()
    x.add(0.dp..32.dp)

    assertEquals(hashBefore, stretch.hashCode())
    assertEquals(
      ImageStretchPixels(
        stretchX = listOf(8f to 24f),
        stretchY = listOf(4f to 28f),
        content = null,
      ),
      stretch.resolve(imageWidth = 32, imageHeight = 32, scale = 1f),
    )
  }
}
