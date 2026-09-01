package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.map.MapRuntimeClosedException
import org.maplibre.compose.map.RuntimeImplementation
import org.maplibre.compose.map.mapRuntimeForTest

class MapRequestInterceptorTest {

  @Test
  fun a_blank_url_rewrite_keeps_the_incoming_url() {
    val interceptor = MapRequestInterceptor { MapRequestTransform(url = "") }
    val transform = interceptor.transform(REQUEST)
    assertEquals(null, transform.url)
  }

  @Test
  fun a_null_interceptor_keeps_the_url_and_adds_no_headers() {
    val transform = (null as MapRequestInterceptor?).transform(REQUEST)
    assertEquals(null, transform.url)
    assertEquals(emptyMap(), transform.headers)
  }

  @Test
  fun an_interceptor_exception_keeps_the_original_request() {
    val interceptor = MapRequestInterceptor { error("token store exploded") }
    val transform = interceptor.transform(REQUEST)
    assertEquals(null, transform.url)
    assertEquals(emptyMap(), transform.headers)
  }

  @Test
  fun a_fatal_interceptor_error_propagates() {
    val interceptor = MapRequestInterceptor { throw OutOfMemoryError("heap") }
    try {
      interceptor.transform(REQUEST)
      error("expected OutOfMemoryError")
    } catch (_: OutOfMemoryError) {}
  }

  @Test
  fun set_request_interceptor_replaces_the_live_callback() {
    val runtime = mapRuntimeForTest()
    val first = MapRequestInterceptor { MapRequestTransform(url = "https://first.example/style") }
    val second = MapRequestInterceptor { MapRequestTransform(url = "https://second.example/style") }
    runtime.setRequestInterceptor(first)
    val config = (runtime as RuntimeImplementation).resourceConfig
    assertEquals("https://first.example/style", config.interceptor().transform(REQUEST).url)
    runtime.setRequestInterceptor(second)
    assertEquals("https://second.example/style", config.interceptor().transform(REQUEST).url)
    runtime.setRequestInterceptor(null)
    assertEquals(null, config.interceptor())
    runtime.close()
  }

  @Test
  fun set_request_interceptor_fails_after_the_runtime_closes() = runTest {
    val runtime = mapRuntimeForTest()
    runtime.close()
    runtime.awaitClosed()
    try {
      runtime.setRequestInterceptor(MapRequestInterceptor { MapRequestTransform() })
      error("expected MapRuntimeClosedException")
    } catch (_: MapRuntimeClosedException) {}
  }

  @Test
  fun an_accepts_exception_declines_the_request() {
    val provider =
      MapResourceProvider(accepts = { error("classifier exploded") }, load = { error("unused") })
    assertFalse(provider.acceptsOrDeclines(REQUEST))
  }

  @Test
  fun a_fatal_accepts_error_propagates() {
    val provider =
      MapResourceProvider(accepts = { throw OutOfMemoryError("heap") }, load = { error("unused") })
    try {
      provider.acceptsOrDeclines(REQUEST)
      error("expected OutOfMemoryError")
    } catch (_: OutOfMemoryError) {}
  }

  @Test
  fun a_scheme_provider_accepts_only_that_scheme() = runTest {
    val provider = MapResourceProvider("app") { "body".encodeToByteArray() }
    assertTrue(provider.accepts(MapResourceRequest("app://style.json", MapResourceKind.Style)))
    assertTrue(provider.accepts(MapResourceRequest("APP://style.json", MapResourceKind.Style)))
    assertFalse(
      provider.accepts(MapResourceRequest("https://example.test/style.json", MapResourceKind.Style))
    )
    val load = provider.load(MapResourceRequest("app://style.json", MapResourceKind.Style))
    val bytes = load as MapResourceLoad.Bytes
    assertEquals("body", bytes.bytes.decodeToString())
  }

  @Test
  fun encode_and_decode_preserve_a_url_with_punctuation() {
    val url = "https://tiles.example.com/style.json?key=a+b&x=1"
    assertEquals(url, decodeResourceUrl(encodeResourceUrl(url)))
  }

  private companion object {
    val REQUEST = MapResourceRequest("https://tiles.example.com/style.json", MapResourceKind.Style)
  }
}
