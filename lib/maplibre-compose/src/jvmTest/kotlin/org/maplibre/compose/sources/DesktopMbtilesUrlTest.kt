package org.maplibre.compose.sources

import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    assertTrue(url.startsWith("mbtiles://"), url)
    val path = decodeResourceUrl(url.removePrefix("mbtiles://"))
    assertEquals(File(URI(uri)).absolutePath, path)
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
    val second = desktopMbtilesPath(uri, directory)

    assertEquals(first, second)
    assertEquals("first", File(first).readText())
    assertEquals(listOf(File(first).name), directory.list()?.toList(), "one copy, no leftovers")
  }

  @Test
  fun `a changed jar entry is copied again`() = runTest {
    val uri =
      fixture.jarEntry("app.jar", "files/city.mbtiles", mapOf("files/city.mbtiles" to "first"))
    val first = desktopMbtilesPath(uri, directory)
    assertEquals("first", File(first).readText())

    fixture.jarEntry("app.jar", "files/city.mbtiles", mapOf("files/city.mbtiles" to "second!"))
    val second = desktopMbtilesPath(uri, directory)

    assertNotEquals(first, second, "a changed resource gets a new copy")
    assertEquals("second!", File(second).readText())
    assertEquals("first", File(first).readText(), "the old copy stays until it is unused")
  }

  @Test
  fun `a copy that no call has used for a month is deleted`() = runTest {
    val old =
      File(directory, "0000.mbtiles").apply {
        parentFile.mkdirs()
        writeText("old")
      }
    old.setLastModified(System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000)
    val uri = fixture.jarEntry("app.jar", "city.mbtiles", mapOf("city.mbtiles" to "new"))

    val copy = desktopMbtilesPath(uri, directory)

    assertFalse(old.exists(), "the unused copy was deleted")
    assertTrue(File(copy).isFile)
  }

  @Test
  fun `entries with the same name keep their own copies`() = runTest {
    val entries = mapOf("Aa/city.mbtiles" to "from Aa", "BB/city.mbtiles" to "from BB")
    val first = fixture.jarEntry("app.jar", "Aa/city.mbtiles", entries)
    val second = fixture.jarEntry("app.jar", "BB/city.mbtiles", entries)

    assertEquals("from Aa", File(desktopMbtilesPath(first, directory)).readText())
    assertEquals("from BB", File(desktopMbtilesPath(second, directory)).readText())
    assertEquals("from Aa", File(desktopMbtilesPath(first, directory)).readText())
  }

  @Test
  fun `anything but a file or a jar entry on disk is rejected as an argument`() = runTest {
    assertFailsWith<IllegalArgumentException> { desktopMbtilesPath("not a uri", directory) }
    assertFailsWith<IllegalArgumentException> { desktopMbtilesPath("nope:tiles", directory) }
    assertFailsWith<IllegalArgumentException> {
      desktopMbtilesPath("https://example.com/city.mbtiles", directory)
    }
    assertFailsWith<IllegalArgumentException> {
      desktopMbtilesPath("jar:https://example.com/app.jar!/city.mbtiles", directory)
    }
    assertFailsWith<IllegalArgumentException> {
      desktopMbtilesPath(
        fixture.jarUri(fixture.root.resolve("missing.jar"), "city.mbtiles"),
        directory,
      )
    }
    assertEquals(emptyList(), directory.list()?.toList().orEmpty(), "nothing was copied")
  }

  @Test
  fun `the URL decodes back to the path`() {
    val path = "/tmp/ké 地図/city.mbtiles"

    val url = mbtilesUrlForPath(path)

    assertEquals("mbtiles:///tmp/k%C3%A9%20%E5%9C%B0%E5%9B%B3/city.mbtiles", url)
    assertEquals(path, decodeResourceUrl(url.removePrefix("mbtiles://")))
  }
}
