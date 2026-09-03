package org.maplibre.compose.resource

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.MlnFfiOwnerThread
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.nativeffi.resource.ResourceKind
import org.maplibre.nativeffi.resource.ResourceLoadingMethod
import org.maplibre.nativeffi.resource.ResourcePriority
import org.maplibre.nativeffi.resource.ResourceRequest
import org.maplibre.nativeffi.resource.ResourceStoragePolicy
import org.maplibre.nativeffi.resource.ResourceUsage

@OptIn(ExperimentalAtomicApi::class)
class MlnFfiRequestHooksTest {

  @Test
  fun url_and_header_callbacks_share_one_interceptor_result() {
    val calls = AtomicInt(0)
    val transforms =
      NativeRequestCoordinator(
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
    val url = "http://tiles.example.com/style.json"
    val incoming = MapResourceRequest(url, MapResourceKind.Style)
    assertEquals(NativeResourceRoute.Fetch, transforms.route(request(url), true))
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
      NativeRequestCoordinator(
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
    routeForFetch(transforms, "https://origin.example/a")
    transforms.rewrittenUrl(MapResourceRequest("https://origin.example/a", MapResourceKind.Style))
    val first =
      transforms.headers(MapResourceRequest("https://cdn.example/a", MapResourceKind.Style))
    routeForFetch(transforms, "https://cdn.example/a")
    transforms.rewrittenUrl(MapResourceRequest("https://cdn.example/a", MapResourceKind.Style))
    val second =
      transforms.headers(MapResourceRequest("https://other.example/a", MapResourceKind.Style))
    assertEquals("Bearer a", first.single().value)
    assertEquals("Bearer b", second.single().value)
  }

  @Test
  fun interleaved_url_callbacks_keep_their_header_transforms() {
    val calls = AtomicInt(0)
    val transforms =
      NativeRequestCoordinator(
        MapResourceConfig(
          interceptor = {
            calls.incrementAndFetch()
            MapRequestTransform(headers = mapOf("Authorization" to "Bearer ${calls.load()}"))
          }
        )
      )
    routeForFetch(transforms, "https://tiles.example.com/a", ResourceKind.TILE)
    transforms.rewrittenUrl(MapResourceRequest("https://tiles.example.com/a", MapResourceKind.Tile))
    routeForFetch(transforms, "https://tiles.example.com/b", ResourceKind.TILE)
    transforms.rewrittenUrl(MapResourceRequest("https://tiles.example.com/b", MapResourceKind.Tile))
    val first =
      transforms.headers(MapResourceRequest("https://tiles.example.com/a", MapResourceKind.Tile))
    val second =
      transforms.headers(MapResourceRequest("https://tiles.example.com/b", MapResourceKind.Tile))
    assertEquals("Bearer 1", first.single().value)
    assertEquals("Bearer 2", second.single().value)
    assertEquals(0, transforms.pendingHeaderCount())
  }

  @Test
  fun two_threads_with_the_same_name_keep_separate_transforms() {
    val transforms =
      NativeRequestCoordinator(
        MapResourceConfig(
          interceptor = {
            MapRequestTransform(headers = mapOf("Authorization" to "Bearer ${it.url}"))
          }
        )
      )
    val firstStored = TestLatch(1)
    val secondStored = TestLatch(1)
    var firstHeader: String? = null
    var secondHeader: String? = null
    val first =
      MlnFfiOwnerThread("http-worker") {
        routeForFetch(transforms, "https://a.example/style.json")
        transforms.rewrittenUrl(
          MapResourceRequest("https://a.example/style.json", MapResourceKind.Style)
        )
        firstStored.countDown()
        check(secondStored.await(10_000)) { "the second thread never stored a transform" }
        firstHeader =
          transforms
            .headers(MapResourceRequest("https://a.example/style.json", MapResourceKind.Style))
            .single()
            .value
      }
    val second =
      MlnFfiOwnerThread("http-worker") {
        check(firstStored.await(10_000)) { "the first thread never stored a transform" }
        routeForFetch(transforms, "https://b.example/style.json")
        transforms.rewrittenUrl(
          MapResourceRequest("https://b.example/style.json", MapResourceKind.Style)
        )
        secondStored.countDown()
        secondHeader =
          transforms
            .headers(MapResourceRequest("https://b.example/style.json", MapResourceKind.Style))
            .single()
            .value
      }
    first.start()
    second.start()
    assertTrue(first.join(10_000), "the first worker never finished")
    assertTrue(second.join(10_000), "the second worker never finished")
    assertEquals("Bearer https://a.example/style.json", firstHeader)
    assertEquals("Bearer https://b.example/style.json", secondHeader)
    assertEquals(0, transforms.pendingHeaderCount())
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

  private fun routeForFetch(
    requests: NativeRequestCoordinator,
    url: String,
    kind: ResourceKind = ResourceKind.STYLE,
  ) {
    assertEquals(NativeResourceRoute.Fetch, requests.route(request(url, kind), true))
  }

  private fun request(url: String, kind: ResourceKind = ResourceKind.STYLE): ResourceRequest =
    ResourceRequest(
      requestedUrl = url,
      resolvedUrl = url,
      kind = kind,
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
