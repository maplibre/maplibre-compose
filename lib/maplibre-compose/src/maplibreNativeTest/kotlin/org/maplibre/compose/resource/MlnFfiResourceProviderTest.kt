package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.nativeffi.resource.ResourceKind
import org.maplibre.nativeffi.resource.ResourceLoadingMethod
import org.maplibre.nativeffi.resource.ResourcePriority
import org.maplibre.nativeffi.resource.ResourceRequest
import org.maplibre.nativeffi.resource.ResourceStoragePolicy
import org.maplibre.nativeffi.resource.ResourceUsage

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
  fun a_rewrite_to_a_network_url_selects_the_native_loader() {
    val config =
      MapResourceConfig(
        interceptor = MapRequestInterceptor(rewriteUrl = { "https://tiles.example.com/style.json" })
      )
    assertEquals(
      NativeResourceRoute.Fetch,
      config.nativeRoute(request("custom://style.json"), true),
    )
  }

  @Test
  fun a_provider_load_keeps_the_style_url_as_the_requested_url() {
    val provider = MapResourceProvider("app") { ByteArray(0) }
    val config =
      MapResourceConfig(
        interceptor = MapRequestInterceptor(rewriteUrl = { "app://style.json" }),
        provider = provider,
      )

    val route = config.nativeRoute(request("custom://style.json"), true)
    assertTrue(route is NativeResourceRoute.Load)
    assertEquals("app://style.json", route.request.url)
    assertEquals("custom://style.json", route.request.requestedUrl)
  }

  @Test
  fun the_packaged_reader_receives_the_rewritten_url() {
    val config =
      MapResourceConfig(
        interceptor = MapRequestInterceptor(rewriteUrl = { "file:/styles/rewritten.json" })
      )
    assertEquals(
      NativeResourceRoute.Read("file:/styles/rewritten.json"),
      config.nativeRoute(request("custom://style.json"), true),
    )
  }

  @Test
  fun a_test_can_keep_a_network_url_with_the_packaged_reader() {
    val config =
      MapResourceConfig(
        interceptor = MapRequestInterceptor(rewriteUrl = { "https://tiles.example.com/style.json" })
      )
    assertEquals(
      NativeResourceRoute.Read("https://tiles.example.com/style.json"),
      config.nativeRoute(request("custom://style.json"), passThroughNetwork = false),
    )
  }

  @Test
  fun a_network_url_can_be_rewritten_to_the_packaged_reader() {
    val config =
      MapResourceConfig(
        interceptor = MapRequestInterceptor(rewriteUrl = { "file:/styles/offline.json" })
      )
    assertEquals(
      NativeResourceRoute.Read("file:/styles/offline.json"),
      config.nativeRoute(request("https://tiles.example.com/style.json"), true),
    )
  }

  private fun request(url: String): ResourceRequest =
    ResourceRequest(
      requestedUrl = url,
      resolvedUrl = url,
      kind = ResourceKind.STYLE,
      loadingMethod = ResourceLoadingMethod.ALL,
      priority = ResourcePriority.REGULAR,
      usage = ResourceUsage.ONLINE,
      storagePolicy = ResourceStoragePolicy.PERMANENT,
      range = null,
      priorModifiedUnixMs = null,
      priorExpiresUnixMs = null,
      priorEtag = null,
      priorData = ByteArray(0),
    )
}
