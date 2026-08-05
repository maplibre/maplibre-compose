package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which side of the resource boundary a URL falls on. */
class DesktopResourceProviderTest {

  @Test
  fun `http and https are MapLibre's, so they keep its caching and revalidation`() {
    assertTrue(isMapLibresToFetch("https://demotiles.maplibre.org/style.json"))
    assertTrue(isMapLibresToFetch("http://example.invalid/tiles/0/0/0.pbf"))
  }

  @Test
  fun `scheme case does not decide ownership`() {
    assertTrue(isMapLibresToFetch("HTTPS://demotiles.maplibre.org/style.json"))
  }

  @Test
  fun `an alias scheme is only MapLibre's once it has been resolved`() {
    // maplibre-native-ffi #467 split the provider's URL in two: `maplibre://maps/style` arrives as
    // the requested URL and the demotiles URL as the resolved one, and ownership is decided on the
    // resolved URL.
    assertFalse(isMapLibresToFetch("maplibre://maps/style"))
    assertTrue(isMapLibresToFetch("https://demotiles.maplibre.org/style.json"))
  }

  @Test
  fun `packaged resource URIs are ours`() {
    assertFalse(isMapLibresToFetch("jar:file:/app/lib/demo.jar!/style.json"))
    assertFalse(isMapLibresToFetch("file:/home/someone/style.json"))
  }

  @Test
  fun `a URL with no scheme is MapLibre's, because nothing here could resolve it`() {
    assertTrue(isMapLibresToFetch("style.json"))
    assertTrue(isMapLibresToFetch(""))
  }

  @Test
  fun `an unparseable URL has no scheme`() {
    assertNull(schemeOf("http://[not a host]/x"))
  }

  @Test
  fun `schemeOf lowercases so callers can compare against lowercase names`() {
    assertEquals("jar", schemeOf("JAR:file:/app.jar!/style.json"))
  }
}
