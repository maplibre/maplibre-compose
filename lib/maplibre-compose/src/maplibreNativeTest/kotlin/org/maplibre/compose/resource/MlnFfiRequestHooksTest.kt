package org.maplibre.compose.resource

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.nativeffi.resource.ResourceKind

@OptIn(ExperimentalAtomicApi::class)
class MlnFfiRequestHooksTest {

  @Test
  fun url_and_header_callbacks_share_one_interceptor_result() {
    val calls = AtomicInt(0)
    val transforms =
      NativeRequestTransforms(
        MapResourceConfig(
          interceptor = { request ->
            calls.incrementAndFetch()
            MapRequestTransform(
              url = request.url.replace("http://", "https://"),
              headers = mapOf("Authorization" to "Bearer ${calls.load()}"),
            )
          }
        )
      )
    val incoming = MapResourceRequest("http://tiles.example.com/style.json", MapResourceKind.Style)
    assertEquals("https://tiles.example.com/style.json", transforms.rewrittenUrl(incoming))
    val headers =
      transforms.headers(
        MapResourceRequest("https://tiles.example.com/style.json", MapResourceKind.Style)
      )
    assertEquals(1, calls.load())
    assertEquals("Authorization", headers.single().name)
    assertEquals("Bearer 1", headers.single().value)
  }

  @Test
  fun a_rewrite_alias_does_not_steal_another_request_headers() {
    val transforms =
      NativeRequestTransforms(
        MapResourceConfig(
          interceptor = { request ->
            when (request.url) {
              "https://origin.example/a" ->
                MapRequestTransform(
                  url = "https://cdn.example/a",
                  headers = mapOf("Authorization" to "Bearer a"),
                )
              "https://cdn.example/a" ->
                MapRequestTransform(
                  url = "https://other.example/a",
                  headers = mapOf("Authorization" to "Bearer b"),
                )
              else -> MapRequestTransform()
            }
          }
        )
      )
    transforms.rewrittenUrl(MapResourceRequest("https://origin.example/a", MapResourceKind.Style))
    val first =
      transforms.headers(MapResourceRequest("https://cdn.example/a", MapResourceKind.Style))
    transforms.rewrittenUrl(MapResourceRequest("https://cdn.example/a", MapResourceKind.Style))
    val second =
      transforms.headers(MapResourceRequest("https://other.example/a", MapResourceKind.Style))
    assertEquals("Bearer a", first.single().value)
    assertEquals("Bearer b", second.single().value)
  }

  @Test
  fun a_later_url_callback_replaces_an_unconsumed_transform() {
    val calls = AtomicInt(0)
    val transforms =
      NativeRequestTransforms(
        MapResourceConfig(
          interceptor = {
            calls.incrementAndFetch()
            MapRequestTransform(headers = mapOf("Authorization" to "Bearer ${calls.load()}"))
          }
        )
      )
    transforms.rewrittenUrl(MapResourceRequest("https://tiles.example.com/a", MapResourceKind.Tile))
    transforms.rewrittenUrl(MapResourceRequest("https://tiles.example.com/a", MapResourceKind.Tile))
    val headers =
      transforms.headers(MapResourceRequest("https://tiles.example.com/a", MapResourceKind.Tile))
    assertEquals("Bearer 2", headers.single().value)
    assertEquals(0, transforms.pendingCount())
  }

  @Test
  fun a_packaged_resource_request_is_not_recorded() {
    val transforms =
      NativeRequestTransforms(
        MapResourceConfig(
          interceptor = {
            MapRequestTransform(
              url = "jar:file:/app.jar!/style.json",
              headers = mapOf("Authorization" to "Bearer a"),
            )
          }
        )
      )
    transforms.rewrittenUrl(
      MapResourceRequest("https://tiles.example.com/style.json", MapResourceKind.Style)
    )
    assertEquals(0, transforms.pendingCount())
  }

  @Test
  fun a_provider_accepted_request_is_not_recorded() {
    val transforms =
      NativeRequestTransforms(
        MapResourceConfig(
          interceptor = {
            MapRequestTransform(
              url = "app://style.json",
              headers = mapOf("Authorization" to "Bearer a"),
            )
          },
          provider = MapResourceProvider("app") { ByteArray(0) },
        )
      )
    transforms.rewrittenUrl(
      MapResourceRequest("https://tiles.example.com/style.json", MapResourceKind.Style)
    )
    assertEquals(0, transforms.pendingCount())
  }

  @Test
  fun every_named_kind_maps_to_the_common_kind() {
    assertEquals(MapResourceKind.Style, ResourceKind.STYLE.toCommon())
    assertEquals(MapResourceKind.Source, ResourceKind.SOURCE.toCommon())
    assertEquals(MapResourceKind.Tile, ResourceKind.TILE.toCommon())
    assertEquals(MapResourceKind.Glyphs, ResourceKind.GLYPHS.toCommon())
    assertEquals(MapResourceKind.SpriteJson, ResourceKind.SPRITE_JSON.toCommon())
    assertEquals(MapResourceKind.SpriteImage, ResourceKind.SPRITE_IMAGE.toCommon())
    assertEquals(MapResourceKind.Image, ResourceKind.IMAGE.toCommon())
    assertEquals(MapResourceKind.Unknown, ResourceKind.UNKNOWN.toCommon())
    assertEquals(MapResourceKind.Unknown, ResourceKind(nativeValue = 99).toCommon())
  }
}
