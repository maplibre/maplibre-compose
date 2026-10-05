package org.maplibre.compose.resource

import js.buffer.ArrayBuffer
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.gljs.ProtocolResponse
import org.maplibre.compose.gljs.RequestParameters

class GlJsRequestControllerTest {

  @Test
  fun an_empty_config_leaves_the_request_unchanged() {
    val controller = GlJsRequestController(MapResourceConfig())
    assertNull(controller.transformRequest("https://tiles.example.com/style.json", "Style"))
    controller.close()
  }

  @Test
  fun an_interceptor_rewrites_the_url_and_adds_headers() {
    val controller =
      GlJsRequestController(
        MapResourceConfig(
          interceptor =
            MapRequestInterceptor(
              rewriteUrl = { request -> request.url.replace("http://", "https://") },
              headers = { mapOf("Authorization" to "Bearer x") },
            )
        )
      )
    val result = controller.transformRequest("http://tiles.example.com/style.json", "Style")
    val dynamic = result.asDynamic()
    assertEquals("https://tiles.example.com/style.json", dynamic.url as String)
    assertEquals("Bearer x", dynamic.headers["Authorization"] as String)
    controller.close()
  }

  @Test
  fun headers_derive_from_the_rewritten_url() {
    val controller =
      GlJsRequestController(
        MapResourceConfig(
          interceptor =
            MapRequestInterceptor(
              rewriteUrl = { request -> request.url.replace("custom://", "https://cdn.example/") },
              headers = { request ->
                if (request.url.startsWith("https://cdn.example/")) {
                  mapOf("Authorization" to "Bearer cdn")
                } else {
                  emptyMap()
                }
              },
            )
        )
      )
    val result = controller.transformRequest("custom://style.json", "Style")
    val dynamic = result.asDynamic()
    assertEquals("https://cdn.example/style.json", dynamic.url as String)
    assertEquals("Bearer cdn", dynamic.headers["Authorization"] as String)
    controller.close()
  }

  @Test
  fun an_interceptor_rewrite_can_select_the_provider() {
    var acceptedUrl: String? = null
    val controller =
      GlJsRequestController(
        MapResourceConfig(
          interceptor = MapRequestInterceptor(rewriteUrl = { "app://style.json" }),
          provider =
            MapResourceProvider(
              accepts = {
                acceptedUrl = it.url
                it.url.startsWith("app:")
              },
              load = { MapResourceLoad.Bytes(ByteArray(0)) },
            ),
        )
      )

    val result = controller.transformRequest("custom://style.json", "Style")
    val parsed = controller.parseProtocolUrl(result.asDynamic().url as String)
    assertEquals("app://style.json", acceptedUrl)
    assertEquals("app://style.json", parsed.url)
    controller.close()
  }

  @Test
  fun an_accepted_provider_rewrites_to_the_runtime_protocol() {
    val controller =
      GlJsRequestController(
        MapResourceConfig(provider = MapResourceProvider("app") { ByteArray(0) })
      )
    val result = controller.transformRequest("app://style.json", "Style")
    val url = result.asDynamic().url as String
    assertTrue(url.startsWith("${controller.scheme}://kStyle/"))
    val parsed = controller.parseProtocolUrl(url)
    assertEquals("app://style.json", parsed.url)
    assertEquals(MapResourceKind.Style, parsed.kind)
    controller.close()
  }

  @Test
  fun a_protocol_url_round_trips_sprite_json() {
    val controller =
      GlJsRequestController(
        MapResourceConfig(provider = MapResourceProvider("app") { ByteArray(0) })
      )
    val result = controller.transformRequest("app://sprite.json", "SpriteJSON")
    val url = result.asDynamic().url as String
    assertTrue(url.startsWith("${controller.scheme}://kSpriteJSON/"))
    val parsed = controller.parseProtocolUrl(url)
    assertEquals("app://sprite.json", parsed.url)
    assertEquals(MapResourceKind.SpriteJson, parsed.kind)
    controller.close()
  }

  @Test
  fun unnamed_browser_kinds_survive_interception_and_provider_loading() = runTest {
    // Include separators, escapes, Unicode, and the empty string in the stored kind identity.
    for (engineKind in listOf("Future/Kind?%#字体", "", "future-kind")) {
      val expected = engineKind.toResourceKind()
      val callbacks = mutableListOf<MapResourceKind>()
      var loaded: MapResourceLoadRequest? = null
      val controller =
        GlJsRequestController(
          MapResourceConfig(
            interceptor =
              MapRequestInterceptor(
                rewriteUrl = {
                  callbacks += it.kind
                  "app://rewritten"
                }
              ),
            provider =
              MapResourceProvider(
                accepts = {
                  callbacks += it.kind
                  true
                },
                load = {
                  loaded = it
                  MapResourceLoad.NoContent()
                },
              ),
          )
        )
      try {
        val transformed = controller.transformRequest("app://original", engineKind)
        val request = transformed.unsafeCast<RequestParameters>()
        controller.loadProtocol(request, js("new AbortController()")).await()
        val load = requireNotNull(loaded)
        assertEquals(listOf(expected, expected), callbacks)
        assertEquals(expected, load.kind)
        assertEquals(engineKind, load.kind.browserValue)
        assertNull(load.kind.nativeValue)
        assertEquals("app://rewritten", load.url)
        assertEquals(MapResourceLoadRequest.LoadingMethod.All, load.loadingMethod)
        assertEquals(MapResourceLoadRequest.Priority.Regular, load.priority)
        assertEquals(MapResourceLoadRequest.Usage.Online, load.usage)
        assertEquals(MapResourceLoadRequest.StoragePolicy.Permanent, load.storagePolicy)
        assertNotEquals(MapResourceKind.Unknown, expected)
        assertNotEquals("another-kind".toResourceKind(), expected)
      } finally {
        controller.close()
      }
    }
  }

  @Test
  fun known_browser_kinds_and_unspecified_requests_keep_their_common_identity() {
    for (kind in
      listOf(
        MapResourceKind.Style,
        MapResourceKind.Source,
        MapResourceKind.Tile,
        MapResourceKind.Glyphs,
        MapResourceKind.SpriteJson,
        MapResourceKind.SpriteImage,
        MapResourceKind.Image,
        MapResourceKind.Unknown,
      )) {
      assertEquals(kind, kind.browserValue.toResourceKind())
    }
    assertEquals(MapResourceKind.Unknown, null.toResourceKind())
  }

  @Test
  fun an_accepts_exception_leaves_the_https_request_unchanged() {
    val controller =
      GlJsRequestController(
        MapResourceConfig(
          provider =
            MapResourceProvider(
              accepts = { error("classifier exploded") },
              load = { error("unused") },
            )
        )
      )
    assertNull(controller.transformRequest("https://tiles.example.com/style.json", "Style"))
    controller.close()
  }

  @Test
  fun each_runtime_rejects_another_runtimes_protocol_url() {
    val first =
      GlJsRequestController(
        MapResourceConfig(provider = MapResourceProvider("app") { ByteArray(0) })
      )
    val second =
      GlJsRequestController(
        MapResourceConfig(provider = MapResourceProvider("app") { ByteArray(0) })
      )
    assertNotEquals(first.scheme, second.scheme)
    val foreign = first.protocolUrl("app://style.json", MapResourceKind.Style)
    assertFails { second.parseProtocolUrl(foreign) }
    first.close()
    second.close()
  }

  @Test
  fun a_rejected_https_url_is_not_rewritten_to_the_protocol() {
    val controller =
      GlJsRequestController(
        MapResourceConfig(provider = MapResourceProvider("app") { ByteArray(0) })
      )
    assertNull(controller.transformRequest("https://tiles.example.com/style.json", "Tile"))
    controller.close()
  }

  @Test
  fun bytes_resolve_with_the_body_and_expiry() = runTest {
    val expires = Instant.fromEpochMilliseconds(1_000)
    val response =
      load(MapResourceLoad.Bytes("body".encodeToByteArray(), expires = expires)).await()
    assertEquals("body", response.data.unsafeCast<ArrayBuffer>().decodeToString())
    assertEquals(1_000.0, response.expires?.getTime())
  }

  @Test
  fun json_requests_resolve_with_decoded_json() = runTest {
    val response =
      load(MapResourceLoad.Bytes("""{"version":8}""".encodeToByteArray()), "json").await()
    assertEquals(8, response.data.asDynamic().version as Int)
  }

  @Test
  fun invalid_json_rejects_the_load() = runTest {
    assertFails { load(MapResourceLoad.Bytes("invalid".encodeToByteArray()), "json").await() }
  }

  @Test
  fun no_content_resolves_with_an_empty_body() = runTest {
    val response = load(MapResourceLoad.NoContent()).await()
    assertEquals(0, response.data.unsafeCast<ArrayBuffer>().byteLength)
    assertNull(response.expires)
  }

  @Test
  fun not_found_rejects_with_status_404() = runTest {
    val error = rejection(MapResourceLoad.Failed(MapResourceError.NotFound, "no tile"))
    assertEquals(404, error.asDynamic().status as Int)
    assertEquals("no tile", error.message)
  }

  @Test
  fun all_failure_categories_keep_their_browser_status() = runTest {
    val statuses =
      mapOf(
        MapResourceError.NotFound to 404,
        MapResourceError.Server to 500,
        MapResourceError.RateLimit to 429,
        MapResourceError.Connection to null,
        MapResourceError.Other to null,
      )
    assertEquals(MapResourceError.entries.toSet(), statuses.keys)
    for ((reason, status) in statuses) {
      val error = rejection(MapResourceLoad.Failed(reason, "failure"))
      assertEquals(status, error.asDynamic().status as Int?)
      assertEquals("failure", error.message)
    }
  }

  @Test
  fun a_reason_without_a_status_rejects_without_one() = runTest {
    val error = rejection(MapResourceLoad.Failed(MapResourceError.Connection, "offline"))
    assertEquals(null, error.asDynamic().status)
  }

  @Test
  fun not_modified_rejects_as_a_provider_error() = runTest {
    val error = rejection(MapResourceLoad.NotModified())
    assertEquals(null, error.asDynamic().status)
    assertTrue(error.message.orEmpty().contains("NotModified"))
  }

  private fun load(result: MapResourceLoad, type: String? = null): Promise<ProtocolResponse> {
    val controller =
      GlJsRequestController(
        MapResourceConfig(provider = MapResourceProvider(accepts = { true }, load = { result }))
      )
    val protocolUrl = controller.protocolUrl("app://tile", MapResourceKind.Tile)
    val request = js("({})").unsafeCast<RequestParameters>()
    request.asDynamic().url = protocolUrl
    request.asDynamic().type = type
    return controller.loadProtocol(request, js("new AbortController()")).also {
      it.then({ controller.close() }, { controller.close() })
    }
  }

  private suspend fun rejection(result: MapResourceLoad): Throwable = assertFails {
    load(result).await()
  }

  private fun ArrayBuffer.decodeToString(): String =
    js("new TextDecoder()").decode(this).unsafeCast<String>()
}
