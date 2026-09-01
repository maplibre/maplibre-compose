package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
  fun a_rejected_https_url_is_not_rewritten_to_the_protocol() {
    val controller =
      GlJsRequestController(
        MapResourceConfig(provider = MapResourceProvider("app") { ByteArray(0) })
      )
    assertNull(controller.transformRequest("https://tiles.example.com/style.json", "Tile"))
    controller.close()
  }
}
