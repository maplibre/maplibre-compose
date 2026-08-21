package org.maplibre.compose.mlnffi

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
  fun a_file_url_names_an_absolute_path() {
    val url = fileUrlOf(Path(directory, "style.json"))

    assertTrue(url.startsWith("file:/"), "an absolute path is a file URI: $url")
    assertFalse(url.contains('\\'), "a URL separates with forward slashes: $url")
    assertTrue(url.endsWith("/style.json"), "the file name survives the conversion: $url")
  }

  @Test
  fun a_file_url_round_trips_back_to_its_path() {
    val file = Path(directory, "round trip.json")

    assertEquals(file, pathOfFileUrl(fileUrlOf(file)))
  }

  @Test
  fun a_file_url_escapes_a_space() {
    val url = fileUrlOf(Path(directory, "round trip.json"))

    assertFalse(url.contains(' '), "a space is reserved and must be escaped: $url")
    assertTrue(url.endsWith("/round%20trip.json"), "the escaped name survives: $url")
  }

  /** The conversion is lexical, so a file that exists reaches the same URL as one that does not. */
  @Test
  fun a_file_url_does_not_depend_on_the_file_existing() {
    val file = Path(directory, "written.json")
    val before = fileUrlOf(file)
    SystemFileSystem.sink(file).close()

    assertEquals(before, fileUrlOf(file))
  }
}
