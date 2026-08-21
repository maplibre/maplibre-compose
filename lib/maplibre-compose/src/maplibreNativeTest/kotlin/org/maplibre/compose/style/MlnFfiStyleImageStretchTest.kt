package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.util.ImageStretch
import org.maplibre.nativeffi.style.ImageContent
import org.maplibre.nativeffi.style.ImageStretch as FfiImageStretch

/** Stretch metadata survives the trip into MapLibre as intervals in image pixels. */
class MlnFfiStyleImageStretchTest {

  @Test
  fun cap_insets_become_a_stretch_box_in_image_pixels() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty, extent = BridgeMapFixture.DEFAULT_EXTENT)
      val style = assertIs<MlnFfiStyle>(it.style, "the style should have reached the callbacks")

      style.addImage(
        IMAGE_ID,
        ImageBitmap(32, 32),
        sdf = false,
        stretch = ImageStretch.capInsets(left = 8.dp, top = 4.dp, right = 8.dp, bottom = 4.dp),
      )

      val info = assertNotNull(it.session.styleImageInfo(IMAGE_ID), "the image should be uploaded")
      assertEquals(ImageContent(8f, 4f, 24f, 28f), info.content, "content box")
      assertEquals(
        listOf(FfiImageStretch(8f, 24f)) to listOf(FfiImageStretch(4f, 28f)),
        style.imageStretches(IMAGE_ID),
        "stretch intervals",
      )
    }
  }

  @Test
  fun cap_insets_on_a_retina_map_scale_with_the_bitmap() {
    val fixture = BridgeMapFixture.create(BridgeMapFixture.RETINA_EXTENT)
    fixture.use {
      it.loadStyle(BaseStyle.Empty, extent = BridgeMapFixture.RETINA_EXTENT)
      val style = assertIs<MlnFfiStyle>(it.style, "the style should have reached the callbacks")

      style.addImage(
        IMAGE_ID,
        ImageBitmap(64, 64),
        sdf = false,
        stretch = ImageStretch.capInsets(left = 8.dp, top = 4.dp, right = 8.dp, bottom = 4.dp),
      )

      val info = assertNotNull(it.session.styleImageInfo(IMAGE_ID), "the image should be uploaded")
      assertEquals(2f, info.pixelRatio, "pixel ratio")
      assertEquals(ImageContent(16f, 8f, 48f, 56f), info.content, "content box")
      assertEquals(
        listOf(FfiImageStretch(16f, 48f)) to listOf(FfiImageStretch(8f, 56f)),
        style.imageStretches(IMAGE_ID),
        "stretch intervals",
      )
    }
  }

  @Test
  fun cap_insets_that_leave_nothing_to_stretch_upload_the_image_whole() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty, extent = BridgeMapFixture.DEFAULT_EXTENT)
      val style = assertIs<MlnFfiStyle>(it.style, "the style should have reached the callbacks")

      style.addImage(
        IMAGE_ID,
        ImageBitmap(32, 32),
        sdf = false,
        stretch = ImageStretch.capInsets(left = 20.dp, top = 4.dp, right = 20.dp, bottom = 4.dp),
      )

      val info =
        assertNotNull(it.session.styleImageInfo(IMAGE_ID), "the image should still be uploaded")
      assertNull(info.content, "content box")
      assertEquals(0L, info.stretchXCount, "horizontal stretch count")
      assertEquals(0L, info.stretchYCount, "vertical stretch count")
      assertEquals(
        emptyList<FfiImageStretch>() to emptyList(),
        style.imageStretches(IMAGE_ID),
        "stretch intervals",
      )
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  @Test
  fun independent_stretch_ranges_round_trip_in_image_pixels() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty, extent = BridgeMapFixture.DEFAULT_EXTENT)
      val style = assertIs<MlnFfiStyle>(it.style, "the style should have reached the callbacks")

      style.addImage(
        IMAGE_ID,
        ImageBitmap(140, 120),
        sdf = false,
        stretch =
          ImageStretch(
            x = listOf(25.dp..55.dp, 85.dp..115.dp),
            y = listOf(25.dp..100.dp),
            content = DpRect(25.dp, 25.dp, 115.dp, 100.dp),
          ),
      )

      val info = assertNotNull(it.session.styleImageInfo(IMAGE_ID), "the image should be uploaded")
      assertEquals(ImageContent(25f, 25f, 115f, 100f), info.content, "content box")
      assertEquals(
        listOf(FfiImageStretch(25f, 55f), FfiImageStretch(85f, 115f)) to
          listOf(FfiImageStretch(25f, 100f)),
        style.imageStretches(IMAGE_ID),
        "stretch intervals",
      )
    }
  }

  @Test
  fun independent_stretch_ranges_on_a_retina_map_scale_with_the_bitmap() {
    val fixture = BridgeMapFixture.create(BridgeMapFixture.RETINA_EXTENT)
    fixture.use {
      it.loadStyle(BaseStyle.Empty, extent = BridgeMapFixture.RETINA_EXTENT)
      val style = assertIs<MlnFfiStyle>(it.style, "the style should have reached the callbacks")

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
