package org.maplibre.compose.util

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.map.FakeImageBitmap
import org.maplibre.compose.map.ResolvedStyleImage

class PreparedImageTest {

  @Test
  fun preparation_owns_its_pixels() {
    val pixels = intArrayOf(OPAQUE_RED, OPAQUE_GREEN)
    val bitmap = FakeImageBitmap(2, 1, pixels)

    val prepared = PreparedImage.fromBitmap(bitmap)
    pixels[0] = OPAQUE_GREEN

    assertEquals(1, bitmap.reads)
    assertEquals(image(2, 1, OPAQUE_RED, OPAQUE_GREEN).pixels, prepared.pixels)
  }

  @Test
  fun bitmap_style_image_owns_its_pixels_and_preserves_options() {
    val pixels = intArrayOf(OPAQUE_RED, OPAQUE_GREEN)
    val bitmap = FakeImageBitmap(2, 1, pixels)
    val stretch = ImageStretch.capInsets(1.dp, 1.dp, 1.dp, 1.dp)

    val defaults = ResolvedStyleImage.fromBitmap(bitmap)
    val styled = ResolvedStyleImage.fromBitmap(image = bitmap, sdf = true, stretch = stretch)
    pixels[0] = OPAQUE_GREEN

    assertEquals(2, bitmap.reads)
    assertEquals(image(2, 1, OPAQUE_RED, OPAQUE_GREEN).pixels, defaults.image.pixels)
    assertEquals(defaults.image.pixels, styled.image.pixels)
    assertFalse(defaults.sdf)
    assertNull(defaults.stretch)
    assertTrue(styled.sdf)
    assertEquals(stretch, styled.stretch)
  }

  @Test
  fun prepared_images_are_equal_only_to_themselves() {
    val first = image(2, 1, OPAQUE_RED, OPAQUE_GREEN)
    val second = image(2, 1, OPAQUE_RED, OPAQUE_GREEN)

    assertEquals(first, first)
    assertNotEquals(first, second)
    assertEquals(first.pixels, second.pixels)
    assertNotEquals(first.pixels, image(2, 1, OPAQUE_RED, OPAQUE_RED).pixels)
    assertNotEquals(first.pixels, image(1, 2, OPAQUE_RED, OPAQUE_GREEN).pixels)
  }

  @Test
  fun an_empty_bitmap_cannot_be_prepared() {
    assertFailsWith<IllegalArgumentException> { PreparedImage.fromBitmap(FakeImageBitmap(0, 1)) }
  }

  @Test
  fun style_image_equality_includes_its_options() {
    val image = image(1, 1, OPAQUE_RED)
    val stretch = ImageStretch.capInsets(1.dp, 1.dp, 1.dp, 1.dp)

    assertEquals(ResolvedStyleImage(image), ResolvedStyleImage(image))
    assertNotEquals(ResolvedStyleImage(image), ResolvedStyleImage(image, sdf = true))
    assertNotEquals(ResolvedStyleImage(image), ResolvedStyleImage(image, stretch = stretch))
  }

  private fun image(width: Int, height: Int, vararg pixels: Int) =
    PreparedImage.fromBitmap(FakeImageBitmap(width, height, pixels))

  private companion object {
    const val OPAQUE_RED = 0xffff0000.toInt()
    const val OPAQUE_GREEN = 0xff00ff00.toInt()
  }
}
