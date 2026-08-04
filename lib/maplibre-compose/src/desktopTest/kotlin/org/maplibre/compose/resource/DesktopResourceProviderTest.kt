package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which side of the resource boundary a URL falls on.
 *
 * The decision every request turns on, and the only part of the provider that is a pure function of
 * the URL. What it does with the requests it keeps is in [DesktopResourceRequestTest], and what it
 * reads for them is in [DesktopResourceReadTest].
 */
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
    // maplibre-native-ffi #467 split the provider's URL in two, and this pair is why it matters. A
    // style naming `maplibre://maps/style` arrives with the alias intact as the requested URL and
    // the demotiles URL as the resolved one. Before the split there was only the alias: this
    // provider claimed it, could not open it, and reported a resource error for a style that loads
    // fine. Deciding on the resolved URL is what fixes that, so both halves are asserted.
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
