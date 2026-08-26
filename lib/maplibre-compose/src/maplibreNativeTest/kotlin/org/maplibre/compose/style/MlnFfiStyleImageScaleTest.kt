package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.BridgeMapFixture

/** MapLibre sizes a style image as `pixels / pixelRatio`. */
class MlnFfiStyleImageScaleTest {

  @Test
  fun an_image_is_tagged_with_the_map_s_scale() {
    assertUploadedPixelRatio(BridgeMapFixture.DEFAULT_EXTENT, expected = 1f)
  }

  @Test
  fun an_image_on_a_retina_map_is_tagged_with_its_scale() {
    assertUploadedPixelRatio(BridgeMapFixture.RETINA_EXTENT, expected = 2f)
  }

  private fun assertUploadedPixelRatio(extent: MapExtent, expected: Float) {
    val fixture = BridgeMapFixture.create(extent)
    fixture.use {
      it.loadStyle(BaseStyle.Empty, extent = extent)

      val style = assertNotNull(it.style, "the style should have reached the callbacks")
      style.addImage(IMAGE_ID, ImageBitmap(SIZE, SIZE), sdf = false, stretch = null)

      val info = assertNotNull(it.core.styleImageInfo(IMAGE_ID), "the image should be in the style")
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
