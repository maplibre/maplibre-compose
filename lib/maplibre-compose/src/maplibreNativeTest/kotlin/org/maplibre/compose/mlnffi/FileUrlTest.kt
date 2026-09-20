package org.maplibre.compose.mlnffi

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.files.Path

/**
 * Covers the `file:` URL the offline tests hand to MapLibre.
 *
 * [fileUrlOf] writes a `file:` URL for a local absolute file. These assertions check that scheme,
 * forward slashes, and percent-encoding of reserved characters.
 */
class FileUrlTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()
  private val directory = requireNotNull(cacheFile.parent)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun file_urls_escape_names_and_round_trip_absolute_paths() {
    for (name in listOf("style.json", "round trip.json")) {
      val file = Path(directory, name)
      val url = fileUrlOf(file)
      assertTrue(url.startsWith("file:/"), url)
      assertFalse(url.contains('\\'), url)
      assertFalse(url.contains(' '), url)
      assertTrue(url.endsWith("/" + name.replace(" ", "%20")), url)
      assertEquals(file, pathOfFileUrl(url))
    }
  }
}
