package org.maplibre.compose.sources

import androidx.compose.ui.graphics.ImageBitmap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.maplibre.compose.desktop.HeadlessMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesktopStyle
import org.maplibre.compose.util.PositionQuad
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.log.LogRecord
import org.maplibre.spatialk.geojson.Position

/**
 * An image source built from a bitmap arrives with its pixels rather than with a placeholder URL.
 *
 * The failure this guards against is silent from the outside: source JSON can only name a URL, so a
 * pixel-backed source added that way is added empty, and the image appears only once something
 * calls `setImage` afterwards. MapLibre reports the difference in one place — it tries to fetch the
 * empty URL and logs `Failed to load source`, with no event and no error the map surfaces — so the
 * test reads its log.
 *
 * Both sources are attached to the same map so the assertion cannot pass vacuously. The URL-less
 * one is the control: it is exactly what the bitmap constructor used to build, its record is what
 * the test waits for, and its arrival is what says the map got far enough to have complained about
 * the other one too.
 */
class ImageSourceAttachTest {

  private val records = ConcurrentLinkedQueue<LogRecord>()

  init {
    // Process-global, and the desktop suite runs in one JVM without parallel forks, so it is
    // installed for the length of one test and taken out again. False keeps native logging.
    Maplibre.setLogCallback { record ->
      records += record
      false
    }
  }

  @AfterTest
  fun clearLogCallback() {
    Maplibre.clearLogCallback()
  }

  @Test
  fun `a bitmap image source is added with its pixels rather than an empty URL`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertIs<DesktopStyle>(it.style, "the style should have reached the callbacks")

      val fromBitmap = ImageSource(BITMAP_SOURCE_ID, WORLD, ImageBitmap(4, 4))
      val fromUrl = ImageSource(URL_SOURCE_ID, WORLD, uri = "")
      style.addSource(fromBitmap)
      style.addSource(fromUrl)

      it.pumpUntil("MapLibre to reject the empty placeholder URL") { failedToLoad(URL_SOURCE_ID) }
      // A few more frames after the control's complaint, so a late one about the other source is
      // not simply being outrun.
      it.pump(frames = 10)

      assertTrue(
        !failedToLoad(BITMAP_SOURCE_ID),
        "The bitmap source should never have been fetched. MapLibre said: " +
          records.map { record -> record.message },
      )
      // The corners are built by hand for the typed adder, and MapLibre accepts any four it is
      // given, so their order is only observable by reading them back.
      assertEquals(
        listOf(WORLD.topLeft, WORLD.topRight, WORLD.bottomRight, WORLD.bottomLeft),
        fromBitmap.attachedCorners(),
      )
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  private fun failedToLoad(sourceId: String): Boolean = records.any {
    it.message.contains("Failed to load source $sourceId")
  }

  private companion object {
    const val BITMAP_SOURCE_ID = "from-bitmap"
    const val URL_SOURCE_ID = "from-url"

    val WORLD =
      PositionQuad(
        topLeft = Position(-180.0, 85.0),
        topRight = Position(180.0, 85.0),
        bottomRight = Position(180.0, -85.0),
        bottomLeft = Position(-180.0, -85.0),
      )
  }
}
