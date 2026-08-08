package org.maplibre.compose.resource

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.maplibre.nativeffi.resource.ResourceErrorReason
import org.maplibre.nativeffi.resource.ResourceResponseStatus

/**
 * Turning a resource URI into bytes, against files that exist and files that do not. Driven
 * directly rather than through a request, because the binding's request handle cannot be
 * constructed outside its own callback.
 */
class DesktopResourceReadTest {

  private val fixture = PackagedResourceFixture()

  @AfterTest
  fun cleanUp() {
    fixture.close()
  }

  private fun read(url: String) = readResource(url, requestedUrl = url, logger = null)

  @Test
  fun `a file whose path contains spaces is read`() {
    val url = fixture.file("style sheets/base style.json", "{\"version\":8}")

    val response = read(url)

    assertContains(
      url,
      "%20",
      message = "the fixture must produce an encoded URI for this to be a test",
    )
    assertEquals(ResourceResponseStatus.OK, response.status, response.errorMessage)
    assertEquals("{\"version\":8}", response.bytes.decodeToString())
  }

  @Test
  fun `a file whose path is not ASCII is read`() {
    val url = fixture.file("スタイル/style.json", "{\"name\":\"地図\"}")

    val response = read(url)

    assertEquals(ResourceResponseStatus.OK, response.status, response.errorMessage)
    assertEquals("{\"name\":\"地図\"}", response.bytes.decodeToString())
  }

  @Test
  fun `packaged resources are read out of a jar`() {
    val url =
      fixture.jarEntry(
        jarName = "demo app.jar",
        entryPath = "composeResources/thé mé/style.json",
        entries =
          mapOf(
            "composeResources/thé mé/style.json" to "{\"packaged\":true}",
            "composeResources/other.json" to "{}",
          ),
      )

    val response = read(url)

    assertEquals(ResourceResponseStatus.OK, response.status, response.errorMessage)
    assertEquals("{\"packaged\":true}", response.bytes.decodeToString())
  }

  @Test
  fun `a read resource is not offered for revalidation`() {
    // Nothing can change a packaged resource while the process runs.
    val response = read(fixture.file("style.json", "{}"))

    assertFalse(response.mustRevalidate)
  }

  @Test
  fun `a missing file is reported as missing`() {
    val url = fixture.missing("style sheets/absent.json")

    val response = read(url)

    assertEquals(ResourceResponseStatus.ERROR, response.status)
    assertEquals(ResourceErrorReason.NOT_FOUND, response.errorReason)
  }

  @Test
  fun `a missing jar entry is reported as missing`() {
    fixture.jarEntry("demo app.jar", "present.json", mapOf("present.json" to "{}"))
    val url = fixture.jarUri(fixture.root.resolve("demo app.jar"), "absent.json")

    val response = read(url)

    assertEquals(ResourceResponseStatus.ERROR, response.status)
    assertEquals(ResourceErrorReason.NOT_FOUND, response.errorReason)
  }

  @Test
  fun `a jar that does not exist is reported as missing, not as unreadable`() {
    // The JDK reports this one from java.nio rather than as a FileNotFoundException.
    val url = fixture.jarUri(fixture.root.resolve("never built.jar"), "style.json")

    val response = read(url)

    assertEquals(ResourceResponseStatus.ERROR, response.status)
    assertEquals(ResourceErrorReason.NOT_FOUND, response.errorReason)
  }

  @Test
  fun `a URL that is not a URI is reported rather than thrown`() {
    // An unencoded space: a caller pasted a path into a `file:` URL instead of asking a Path.
    val response = read("file:/home/someone/my styles/style.json")

    assertEquals(ResourceResponseStatus.ERROR, response.status)
    assertEquals(ResourceErrorReason.OTHER, response.errorReason)
  }

  @Test
  fun `a failure names both URLs when the loader resolved a different one`() {
    // The style names one URL and MapLibre may resolve another.
    val requested = "maplibre://styles/absent.json"
    val resolved = fixture.missing("absent.json")

    val response = readResource(resolved, requestedUrl = requested, logger = null)

    val message = response.errorMessage.orEmpty()
    assertContains(message, requested)
    assertContains(message, resolved)
  }
}
