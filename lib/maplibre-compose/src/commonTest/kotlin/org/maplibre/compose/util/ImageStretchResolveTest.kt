package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImageStretchResolveTest {

  @Test
  fun cap_insets_become_a_stretch_box_in_image_pixels() {
    val resolution =
      ImageStretch.capInsets(left = 8.dp, top = 4.dp, right = 8.dp, bottom = 4.dp)
        .resolve(imageWidth = 32, imageHeight = 32, scale = 1f)

    assertNull(resolution.warning, "valid insets")
    assertEquals(
      ImageStretchPixels(
        stretchX = listOf(8f to 24f),
        stretchY = listOf(4f to 28f),
        content = ImageContentBox(8f, 4f, 24f, 28f),
      ),
      resolution.pixels,
    )
  }

  @Test
  fun cap_insets_on_a_retina_image_scale_with_the_bitmap() {
    val resolution =
      ImageStretch.capInsets(left = 8.dp, top = 4.dp, right = 8.dp, bottom = 4.dp)
        .resolve(imageWidth = 64, imageHeight = 64, scale = 2f)

    assertNull(resolution.warning, "valid insets")
    assertEquals(
      ImageStretchPixels(
        stretchX = listOf(16f to 48f),
        stretchY = listOf(8f to 56f),
        content = ImageContentBox(16f, 8f, 48f, 56f),
      ),
      resolution.pixels,
    )
  }

  @Test
  fun cap_insets_that_leave_nothing_to_stretch_drop_stretch_and_content() {
    val resolution =
      ImageStretch.capInsets(left = 20.dp, top = 4.dp, right = 20.dp, bottom = 4.dp)
        .resolve(imageWidth = 32, imageHeight = 32, scale = 1f)

    assertNotNull(resolution.warning, "overlapping insets")
    assertEquals(ImageStretchPixels(), resolution.pixels)
  }

  @Test
  fun cap_insets_can_inset_the_content_box_further_than_the_stretch_border() {
    val resolution =
      ImageStretch.capInsets(
          stretch = PaddingValues.Absolute(8.dp, 4.dp, 8.dp, 4.dp),
          content = PaddingValues.Absolute(10.dp, 6.dp, 10.dp, 6.dp),
        )
        .resolve(imageWidth = 32, imageHeight = 32, scale = 1f)

    assertNull(resolution.warning, "valid insets")
    assertEquals(
      ImageStretchPixels(
        stretchX = listOf(8f to 24f),
        stretchY = listOf(4f to 28f),
        content = ImageContentBox(10f, 6f, 22f, 26f),
      ),
      resolution.pixels,
    )
  }

  @Test
  fun ranges_keep_stretch_and_content_independent() {
    val resolution =
      ImageStretch(
          x = listOf(12.5.dp..27.5.dp, 42.5.dp..57.5.dp),
          y = listOf(12.5.dp..50.dp),
          content = DpRect(12.5.dp, 12.5.dp, 57.5.dp, 50.dp),
        )
        .resolve(imageWidth = 140, imageHeight = 120, scale = 2f)

    assertNull(resolution.warning, "valid ranges")
    assertEquals(
      ImageStretchPixels(
        stretchX = listOf(25f to 55f, 85f to 115f),
        stretchY = listOf(25f to 100f),
        content = ImageContentBox(25f, 25f, 115f, 100f),
      ),
      resolution.pixels,
    )
  }

  @Test
  fun overlapping_ranges_on_one_axis_drop_that_axis() {
    val resolution =
      ImageStretch(
          x = listOf(4.dp..20.dp, 12.dp..28.dp),
          y = listOf(4.dp..28.dp),
          content = DpRect(4.dp, 4.dp, 28.dp, 28.dp),
        )
        .resolve(imageWidth = 32, imageHeight = 32, scale = 1f)

    assertNotNull(resolution.warning, "overlapping horizontal ranges")
    assertEquals(
      ImageStretchPixels(
        stretchX = emptyList(),
        stretchY = listOf(4f to 28f),
        content = ImageContentBox(4f, 4f, 28f, 28f),
      ),
      resolution.pixels,
    )
  }

  @Test
  fun empty_ranges_omit_stretch_and_keep_a_content_box() {
    val resolution =
      ImageStretch(x = emptyList(), y = emptyList(), content = DpRect(8.dp, 4.dp, 24.dp, 28.dp))
        .resolve(imageWidth = 32, imageHeight = 32, scale = 1f)

    assertNull(resolution.warning, "content only")
    assertEquals(
      ImageStretchPixels(content = ImageContentBox(8f, 4f, 24f, 28f)),
      resolution.pixels,
    )
  }
}
