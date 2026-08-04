package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.maplibre.compose.desktop.HeadlessMapFixture
import org.maplibre.compose.util.ImageResizeOptions
import org.maplibre.nativeffi.style.ImageContent
import org.maplibre.nativeffi.style.ImageStretch

/**
 * Content insets survive the trip into MapLibre as a stretch box in image pixels.
 *
 * The insets a caller gives are distances in from each edge in [androidx.compose.ui.unit.Dp], and
 * what MapLibre stores is an interval per axis plus a content box in pixels — two conversions that
 * are easy to get right at 1x and wrong everywhere else. These read the numbers back off the map
 * rather than trusting that the upload did not throw.
 */
class DesktopStyleImageInsetsTest {

  @Test
  fun `insets become a stretch box in image pixels`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty, extent = HeadlessMapFixture.DEFAULT_EXTENT)
      val style = assertIs<DesktopStyle>(it.style, "the style should have reached the callbacks")

      style.addImage(
        IMAGE_ID,
        ImageBitmap(32, 32),
        sdf = false,
        resizeOptions = ImageResizeOptions(left = 8.dp, top = 4.dp, right = 8.dp, bottom = 4.dp),
      )

      val info = assertNotNull(it.session.styleImageInfo(IMAGE_ID), "the image should be uploaded")
      // Measured from the top-left, so the far sides are the image's size less the inset.
      assertEquals(ImageContent(8f, 4f, 24f, 28f), info.content, "content box")
      assertEquals(
        listOf(ImageStretch(8f, 24f)) to listOf(ImageStretch(4f, 28f)),
        style.imageStretches(IMAGE_ID),
        "stretch intervals",
      )
    }
  }

  @Test
  fun `insets on a retina map scale with the bitmap`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty, extent = HeadlessMapFixture.RETINA_EXTENT)
      val style = assertIs<DesktopStyle>(it.style, "the style should have reached the callbacks")

      // The same logical image as above: a 2x display rasterizes it into twice as many pixels, so
      // the insets have to land twice as far in to describe the same border.
      style.addImage(
        IMAGE_ID,
        ImageBitmap(64, 64),
        sdf = false,
        resizeOptions = ImageResizeOptions(left = 8.dp, top = 4.dp, right = 8.dp, bottom = 4.dp),
      )

      val info = assertNotNull(it.session.styleImageInfo(IMAGE_ID), "the image should be uploaded")
      assertEquals(2f, info.pixelRatio, "pixel ratio")
      assertEquals(ImageContent(16f, 8f, 48f, 56f), info.content, "content box")
      assertEquals(
        listOf(ImageStretch(16f, 48f)) to listOf(ImageStretch(8f, 56f)),
        style.imageStretches(IMAGE_ID),
        "stretch intervals",
      )
    }
  }

  @Test
  fun `insets that leave nothing to stretch upload the image whole`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty, extent = HeadlessMapFixture.DEFAULT_EXTENT)
      val style = assertIs<DesktopStyle>(it.style, "the style should have reached the callbacks")

      // 20 + 20 in from the sides of a 32-pixel image crosses over: the left edge of the box lands
      // to the right of its right edge, which MapLibre would divide by zero over.
      style.addImage(
        IMAGE_ID,
        ImageBitmap(32, 32),
        sdf = false,
        resizeOptions = ImageResizeOptions(left = 20.dp, top = 4.dp, right = 20.dp, bottom = 4.dp),
      )

      val info =
        assertNotNull(it.session.styleImageInfo(IMAGE_ID), "the image should still be uploaded")
      assertNull(info.content, "content box")
      assertEquals(0L, info.stretchXCount, "horizontal stretch count")
      // The vertical axis is dropped with the horizontal one: half a nine-patch is not a thing
      // MapLibre draws, and one axis of stretch on an image asking for both is a worse lie than
      // none.
      assertEquals(0L, info.stretchYCount, "vertical stretch count")
      assertEquals(
        emptyList<ImageStretch>() to emptyList(),
        style.imageStretches(IMAGE_ID),
        "stretch intervals",
      )
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  private companion object {
    const val IMAGE_ID = "insets-test"
  }
}
