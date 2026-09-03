package org.maplibre.compose.sources

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.resource.PackagedResourceFixture
import org.maplibre.compose.resource.decodeResourceUrl

class DesktopMbtilesUrlTest {

  private val fixture = PackagedResourceFixture()
  private val directory = Files.createTempDirectory("mbtiles copies").toFile()

  @AfterTest
  fun cleanUp() {
    fixture.close()
    directory.deleteRecursively()
  }

  @Test
  fun `a file on disk is referenced in place`() = runTest {
    val uri = fixture.file("tiles/city map.mbtiles", "sqlite")

    val url = mbtilesUrlForPath(desktopMbtilesPath(uri, directory))

    assertTrue(url.startsWith("mbtiles:///"), url)
    val path = decodeResourceUrl(url.removePrefix("mbtiles://"))
    assertEquals("sqlite", File(path).readText())
    assertEquals(emptyList(), directory.list()?.toList().orEmpty(), "nothing was copied")
  }

  @Test
  fun `a jar entry is copied once and reused`() = runTest {
    val uri =
      fixture.jarEntry(
        "app.jar",
        "composeResources/files/city.mbtiles",
        mapOf("composeResources/files/city.mbtiles" to "first"),
      )

    val first = desktopMbtilesPath(uri, directory)
    val copiedAt = File(first).lastModified()
    Thread.sleep(20)
    val second = desktopMbtilesPath(uri, directory)

    assertEquals(first, second)
    assertEquals("first", File(first).readText())
    assertEquals(copiedAt, File(second).lastModified(), "the second call reused the copy")
    assertTrue(File(first).name.endsWith("city.mbtiles"), first)
  }

  @Test
  fun `a changed jar entry is copied again`() = runTest {
    val uri =
      fixture.jarEntry("app.jar", "files/city.mbtiles", mapOf("files/city.mbtiles" to "first"))
    val first = desktopMbtilesPath(uri, directory)
    assertEquals("first", File(first).readText())

    Thread.sleep(20)
    fixture.jarEntry("app.jar", "files/city.mbtiles", mapOf("files/city.mbtiles" to "second!"))
    val second = desktopMbtilesPath(uri, directory)

    assertEquals(first, second)
    assertEquals("second!", File(second).readText())
    assertNotEquals("first", File(second).readText())
  }

  @Test
  fun `the URL decodes back to the path`() {
    val path = "/tmp/ké 地図/city.mbtiles"

    val url = mbtilesUrlForPath(path)

    assertEquals("mbtiles:///tmp/k%C3%A9%20%E5%9C%B0%E5%9B%B3/city.mbtiles", url)
    assertEquals(path, decodeResourceUrl(url.removePrefix("mbtiles://")))
  }
}
