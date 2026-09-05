package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.logging.MapLogLevel
import org.maplibre.compose.logging.MapLogRecord
import org.maplibre.compose.logging.MapLogger
import org.maplibre.compose.logging.MapLogging

class MapRequestInterceptorTest {

  @Test
  fun a_blank_rewrite_keeps_the_incoming_url() {
    val interceptor = MapRequestInterceptor(rewriteUrl = { "   " })
    assertEquals(REQUEST.url, interceptor.rewrittenUrl(REQUEST, null))
  }

  @Test
  fun a_null_interceptor_keeps_the_url_and_adds_no_headers() {
    val interceptor: MapRequestInterceptor? = null
    assertEquals(REQUEST.url, interceptor.rewrittenUrl(REQUEST, null))
    assertEquals(emptyMap(), interceptor.headersOrNone(REQUEST, null))
  }

  @Test
  fun an_interceptor_exception_keeps_the_original_request() {
    val interceptor =
      MapRequestInterceptor(
        rewriteUrl = { error("token store exploded") },
        headers = { error("token store exploded") },
      )
    assertEquals(REQUEST.url, interceptor.rewrittenUrl(REQUEST, null))
    assertEquals(emptyMap(), interceptor.headersOrNone(REQUEST, null))
  }

  @Test
  fun an_interceptor_exception_is_logged_as_a_warning() {
    val records = mutableListOf<MapLogRecord>()
    val previous = MapLogging.logger
    MapLogging.logger = MapLogger { records += it }
    try {
      val interceptor = MapRequestInterceptor(rewriteUrl = { error("token store exploded") })
      assertEquals(REQUEST.url, interceptor.rewrittenUrl(REQUEST, MapLog))
    } finally {
      MapLogging.logger = previous
    }
    val record = records.single()
    assertEquals(MapLogLevel.Warning, record.level)
    assertEquals("token store exploded", record.throwable?.message)
    assertTrue(REQUEST.url in record.message)
  }

  @Test
  fun a_fatal_interceptor_error_propagates() {
    val interceptor = MapRequestInterceptor(rewriteUrl = { throw FatalTestError() })
    try {
      interceptor.rewrittenUrl(REQUEST, null)
      error("expected FatalTestError")
    } catch (_: FatalTestError) {}
  }

  @Test
  fun the_provider_receives_the_rewritten_url() {
    var acceptedUrl: String? = null
    val provider =
      MapResourceProvider(
        accepts = {
          acceptedUrl = it.url
          it.url.startsWith("app:")
        },
        load = { MapResourceLoad.Bytes(ByteArray(0)) },
      )
    val config =
      MapResourceConfig(
        interceptor = MapRequestInterceptor(rewriteUrl = { "app://style.json" }),
        provider = provider,
      )
    val route = config.route(MapResourceRequest("custom://style.json", MapResourceKind.Style))
    val expected = MapResourceRequest("app://style.json", MapResourceKind.Style)
    assertEquals(MapResourceRoute.Load(expected, provider), route)
    assertEquals("app://style.json", acceptedUrl)
  }

  @Test
  fun an_accepts_exception_declines_the_request() {
    val provider =
      MapResourceProvider(accepts = { error("classifier exploded") }, load = { error("unused") })
    assertFalse(provider.acceptsOrDeclines(REQUEST, null))
  }

  @Test
  fun a_fatal_accepts_error_propagates() {
    val provider =
      MapResourceProvider(accepts = { throw FatalTestError() }, load = { error("unused") })
    try {
      provider.acceptsOrDeclines(REQUEST, null)
      error("expected FatalTestError")
    } catch (_: FatalTestError) {}
  }

  @Test
  fun a_scheme_provider_accepts_only_that_scheme() = runTest {
    val provider = MapResourceProvider("app") { "body".encodeToByteArray() }
    assertTrue(provider.accepts(MapResourceRequest("app://style.json", MapResourceKind.Style)))
    assertTrue(provider.accepts(MapResourceRequest("APP://style.json", MapResourceKind.Style)))
    assertFalse(
      provider.accepts(MapResourceRequest("https://example.test/style.json", MapResourceKind.Style))
    )
    val load = provider.load(MapResourceLoadRequest("app://style.json", MapResourceKind.Style))
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

private class FatalTestError : Error("heap")
