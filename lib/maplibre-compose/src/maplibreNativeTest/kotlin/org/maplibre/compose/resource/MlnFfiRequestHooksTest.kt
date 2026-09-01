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
