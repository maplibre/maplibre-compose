package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.map.FakeImageBitmap

class ImageManagerTest {

  @Test
  fun distinct_bitmaps_with_identical_pixels_share_one_image() {
    val manager = ImageManager(StyleNode(RecordingStyleBinding()))
    val first = ImageManager.BitmapKey(FakeImageBitmap(2, 2), isSdf = false, stretch = null)
    val second = ImageManager.BitmapKey(FakeImageBitmap(2, 2), isSdf = false, stretch = null)

    val firstId = manager.acquireBitmap(first)
    val secondId = manager.acquireBitmap(second)

    assertEquals(firstId, secondId)
    assertEquals(1, manager.desiredImages.size)

    manager.releaseBitmap(first)
    assertEquals(1, manager.desiredImages.size)

    manager.releaseBitmap(second)
    assertTrue(manager.desiredImages.isEmpty())
  }
}
