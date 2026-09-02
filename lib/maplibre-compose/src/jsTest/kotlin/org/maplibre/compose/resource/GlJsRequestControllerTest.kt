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
          interceptor = { request ->
            MapRequestTransform(
              url = request.url.replace("http://", "https://"),
              headers = mapOf("Authorization" to "Bearer x"),
            )
          }
        )
      )
    val result = controller.transformRequest("http://tiles.example.com/style.json", "Style")
    val dynamic = result.asDynamic()
    assertEquals("https://tiles.example.com/style.json", dynamic.url as String)
    assertEquals("Bearer x", dynamic.headers["Authorization"] as String)
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
    assertTrue(url.startsWith("${controller.scheme}://Style/"))
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
    assertTrue(url.startsWith("${controller.scheme}://SpriteJson/"))
    val parsed = controller.parseProtocolUrl(url)
    assertEquals("app://sprite.json", parsed.url)
    assertEquals(MapResourceKind.SpriteJson, parsed.kind)
    controller.close()
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
  fun a_forged_protocol_url_is_declined() {
    val controller =
      GlJsRequestController(
        MapResourceConfig(provider = MapResourceProvider("app") { ByteArray(0) })
      )
    val forged = controller.protocolUrl("https://evil.example/style.json", MapResourceKind.Style)
    try {
      controller.requireAccepted(forged)
      error("expected the provider to decline the forged URL")
    } catch (error: IllegalStateException) {
      assertTrue(error.message.orEmpty().contains("declined"))
    }
    controller.close()
  }

  @Test
  fun each_runtime_registers_an_unguessable_protocol_scheme() {
    val first =
      GlJsRequestController(
        MapResourceConfig(provider = MapResourceProvider("app") { ByteArray(0) })
      )
    val second =
      GlJsRequestController(
        MapResourceConfig(provider = MapResourceProvider("app") { ByteArray(0) })
      )
    assertTrue(SCHEME.matches(first.scheme))
    assertTrue(SCHEME.matches(second.scheme))
    assertNotEquals(first.scheme, second.scheme)
    val foreign = first.protocolUrl("app://style.json", MapResourceKind.Style)
    assertFails { second.requireAccepted(foreign) }
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
    assertEquals("body", response.data.decodeToString())
    assertEquals(1_000.0, response.expires?.getTime())
  }

  @Test
  fun no_content_resolves_with_an_empty_body() = runTest {
    val response = load(MapResourceLoad.NoContent()).await()
    assertEquals(0, response.data.byteLength)
    assertNull(response.expires)
  }

  @Test
  fun not_found_rejects_with_status_404() = runTest {
    val error = rejection(MapResourceLoad.Failed(MapResourceError.NotFound, "no tile"))
    assertEquals(404, error.asDynamic().status as Int)
    assertEquals("no tile", error.message)
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

  private fun load(result: MapResourceLoad): Promise<ProtocolResponse> {
    val controller =
      GlJsRequestController(
        MapResourceConfig(provider = MapResourceProvider(accepts = { true }, load = { result }))
      )
    val protocolUrl = controller.protocolUrl("app://tile", MapResourceKind.Tile)
    val request = js("({})").unsafeCast<RequestParameters>()
    request.asDynamic().url = protocolUrl
    return controller.loadProtocol(request, js("new AbortController()")).also {
      it.then({ controller.close() }, { controller.close() })
    }
  }

  private suspend fun rejection(result: MapResourceLoad): Throwable =
    try {
      load(result).await()
      error("expected the protocol promise to reject")
    } catch (error: Throwable) {
      error
    }

  private fun ArrayBuffer.decodeToString(): String =
    js("new TextDecoder()").decode(this).unsafeCast<String>()

  private companion object {
    val SCHEME = Regex("^mlc-res-[0-9a-f]{32}$")
  }
}
