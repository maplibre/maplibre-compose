package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.util.ImageStretch
import org.maplibre.nativeffi.style.ImageContent
import org.maplibre.nativeffi.style.ImageStretch as FfiImageStretch

/** Stretch metadata survives the trip into MapLibre as intervals in image pixels. */
class MlnFfiStyleImageStretchTest {

  @Test
  fun independent_stretch_ranges_on_a_retina_map_scale_with_the_bitmap() {
    val fixture = BridgeMapFixture.create(BridgeMapFixture.RETINA_EXTENT)
    fixture.use {
      it.loadStyle(BaseStyle.Empty, extent = BridgeMapFixture.RETINA_EXTENT)
      val style =
        assertIs<MlnFfiStyleBinding>(it.style, "the style should have reached the callbacks")

      style.addImage(
        IMAGE_ID,
        ImageBitmap(140, 120),
        sdf = false,
        stretch =
          ImageStretch(
            x = listOf(12.5.dp..27.5.dp, 42.5.dp..57.5.dp),
            y = listOf(12.5.dp..50.dp),
            content = DpRect(12.5.dp, 12.5.dp, 57.5.dp, 50.dp),
          ),
      )

      val info = assertNotNull(it.session.styleImageInfo(IMAGE_ID), "the image should be uploaded")
      assertEquals(2f, info.pixelRatio, "pixel ratio")
      assertEquals(140, info.width, "the uploaded bitmap remains unscaled")
      assertEquals(ImageContent(25f, 25f, 115f, 100f), info.content, "content box")
      assertEquals(
        listOf(FfiImageStretch(25f, 55f), FfiImageStretch(85f, 115f)) to
          listOf(FfiImageStretch(25f, 100f)),
        style.imageStretches(IMAGE_ID),
        "stretch intervals",
      )
    }
  }

  private companion object {
    const val IMAGE_ID = "stretch-test"
  }
}
