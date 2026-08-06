package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.maplibre.compose.mlnffi.HeadlessMapFixture
import org.maplibre.compose.mlnffi.MlnFfiMapExtent

/**
 * A style image is uploaded with the scale it was rasterized at: MapLibre sizes a style image as
 * `pixels / pixelRatio`, so a wrong ratio draws every icon at the wrong size on hi-dpi displays.
 */
class DesktopStyleImageScaleTest {

  @Test
  fun `an image is tagged with the map's scale`() {
    assertUploadedPixelRatio(HeadlessMapFixture.DEFAULT_EXTENT, expected = 1f)
  }

  @Test
  fun `an image on a retina map is tagged with its scale`() {
    assertUploadedPixelRatio(HeadlessMapFixture.RETINA_EXTENT, expected = 2f)
  }

  private fun assertUploadedPixelRatio(extent: MlnFfiMapExtent, expected: Float) {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty, extent = extent)

      val style = assertNotNull(it.style, "the style should have reached the callbacks")
      style.addImage(IMAGE_ID, ImageBitmap(SIZE, SIZE), sdf = false, resizeOptions = null)

      val info =
        assertNotNull(it.session.styleImageInfo(IMAGE_ID), "the image should be in the style")
      assertEquals(expected, info.pixelRatio, "pixel ratio")
      // The upload itself is unscaled; only the ratio it is tagged with changes.
      assertEquals(SIZE, info.width, "width")
    }
  }

  private companion object {
    const val IMAGE_ID = "scale-test"
    const val SIZE = 32
  }
}
