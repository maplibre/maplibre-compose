package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which side of the resource boundary a URL falls on. */
class MlnFfiResourceProviderTest {

  @Test
  fun http_and_https_are_maplibre_s_so_they_keep_its_caching_and_revalidation() {
    assertTrue(isMapLibresToFetch("https://demotiles.maplibre.org/style.json"))
    assertTrue(isMapLibresToFetch("http://example.invalid/tiles/0/0/0.pbf"))
  }

  @Test
  fun scheme_case_does_not_decide_ownership() {
    assertTrue(isMapLibresToFetch("HTTPS://demotiles.maplibre.org/style.json"))
  }

  @Test
  fun an_alias_scheme_is_only_maplibre_s_once_it_has_been_resolved() {
    // maplibre-native-ffi #467 split the provider's URL in two: `maplibre://maps/style` arrives as
    // the requested URL and the demotiles URL as the resolved one, and ownership is decided on the
    // resolved URL.
    assertFalse(isMapLibresToFetch("maplibre://maps/style"))
    assertTrue(isMapLibresToFetch("https://demotiles.maplibre.org/style.json"))
  }

  @Test
  fun packaged_resource_uris_are_ours() {
    assertFalse(isMapLibresToFetch("jar:file:/app/lib/demo.jar!/style.json"))
    assertFalse(isMapLibresToFetch("file:/home/someone/style.json"))
  }

  @Test
  fun a_url_with_no_scheme_is_maplibre_s_because_nothing_here_could_resolve_it() {
    assertTrue(isMapLibresToFetch("style.json"))
    assertTrue(isMapLibresToFetch(""))
  }

  @Test
  fun an_unparseable_url_has_no_scheme() {
    assertNull(schemeOf("http://[not a host]/x"))
  }

  @Test
  fun a_windows_path_has_no_scheme_so_its_drive_letter_stays_out_of_the_provider() {
    assertNull(schemeOf("C:\\Users\\someone\\style.json"))
    assertTrue(isMapLibresToFetch("C:\\Users\\someone\\style.json"))
  }

  @Test
  fun a_scheme_starts_with_a_letter() {
    assertNull(schemeOf("2fast:/style.json"))
    assertNull(schemeOf(":/style.json"))
    assertNull(schemeOf("/home/someone/style:json"))
  }

  @Test
  fun a_scheme_may_hold_digits_and_punctuation_after_its_first_letter() {
    assertEquals("view-source", schemeOf("view-source:https://example.invalid/"))
    assertEquals("z39.50r", schemeOf("z39.50r:host/db"))
    assertEquals("soap.beep+tls", schemeOf("soap.beep+tls:host"))
  }

  @Test
  fun schemeof_lowercases_so_callers_can_compare_against_lowercase_names() {
    assertEquals("jar", schemeOf("JAR:file:/app.jar!/style.json"))
  }

  @Test
  fun a_network_url_passes_through_without_consulting_the_interceptor() {
    var calls = 0
    val interceptor = MapRequestInterceptor {
      calls += 1
      MapRequestTransform(url = "custom://unused")
    }
    assertTrue(
      shouldPassThroughToEngine(
        MapResourceRequest("https://demotiles.maplibre.org/style.json", MapResourceKind.Style),
        interceptor,
      )
    )
    assertEquals(0, calls)
  }

  @Test
  fun a_custom_scheme_without_a_rewrite_stays_with_the_provider() {
    assertFalse(
      shouldPassThroughToEngine(
        MapResourceRequest("custom://style.json", MapResourceKind.Style),
        interceptor = null,
      )
    )
  }

  @Test
  fun a_custom_scheme_rewritten_to_https_passes_through() {
    val seen = mutableListOf<String>()
    val interceptor = MapRequestInterceptor { request ->
      seen += request.url
      MapRequestTransform(url = "https://tiles.example.com/style.json")
    }
    assertTrue(
      shouldPassThroughToEngine(
        MapResourceRequest("custom://style.json", MapResourceKind.Style),
        interceptor,
      )
    )
    assertEquals(listOf("custom://style.json"), seen)
  }

  @Test
  fun a_custom_scheme_rewritten_to_another_custom_scheme_stays_with_the_provider() {
    val interceptor = MapRequestInterceptor { MapRequestTransform(url = "app://style.json") }
    assertFalse(
      shouldPassThroughToEngine(
        MapResourceRequest("custom://style.json", MapResourceKind.Style),
        interceptor,
      )
    )
  }

  @Test
  fun a_packaged_resource_rewritten_to_https_passes_through() {
    val interceptor = MapRequestInterceptor {
      MapRequestTransform(url = "https://tiles.example.com/style.json")
    }
    assertTrue(
      shouldPassThroughToEngine(
        MapResourceRequest("jar:file:/app.jar!/style.json", MapResourceKind.Style),
        interceptor,
      )
    )
  }
}
