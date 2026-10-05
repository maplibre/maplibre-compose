package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RecordingList

class MlnFfiRequestInterceptorCustomSchemeTest {

  @Test
  fun an_engine_style_request_reaches_the_provider_with_its_classifications() {
    val intercepted = RecordingList<MapResourceKind>()
    val accepted = RecordingList<MapResourceKind>()
    val loaded = RecordingList<MapResourceLoadRequest>()
    BridgeMapFixture.create(
        resourceConfig =
          MapResourceConfig(
            interceptor =
              MapRequestInterceptor(
                rewriteUrl = {
                  intercepted += it.kind
                  "app://style.json"
                }
              ),
            provider =
              MapResourceProvider(
                accepts = {
                  accepted += it.kind
                  it.url.startsWith("app:")
                },
                load = {
                  loaded += it
                  MapResourceLoad.Bytes(
                    """{"version":8,"sources":{},"layers":[]}""".encodeToByteArray()
                  )
                },
              ),
          )
      )
      .use {
        it.session.setBaseStyle(BaseStyle.Uri("custom://style.json"))
        it.pumpUntil("the provider style to load") { it.style?.isLoaded == true }
        assertTrue(it.errors.isEmpty(), it.errors.toString())
      }
    assertEquals(listOf(MapResourceKind.Style), intercepted.toList())
    assertEquals(listOf(MapResourceKind.Style), accepted.toList())
    val request = loaded.toList().single()
    assertEquals(MapResourceKind.Style, request.kind)
    assertEquals("app://style.json", request.url)
    assertEquals(MapResourceLoadRequest.LoadingMethod.All, request.loadingMethod)
    assertEquals(MapResourceLoadRequest.Priority.Regular, request.priority)
    assertEquals(MapResourceLoadRequest.Usage.Online, request.usage)
    assertEquals(MapResourceLoadRequest.StoragePolicy.Permanent, request.storagePolicy)
  }

  @Test
  fun the_engine_fetches_the_rewritten_url_and_asks_it_for_headers() {
    val rewritten = RecordingList<String>()
    val headerUrls = RecordingList<String>()
    val fixture =
      BridgeMapFixture.create(
        resourceConfig =
          MapResourceConfig(
            interceptor =
              MapRequestInterceptor(
                rewriteUrl = { request ->
                  rewritten += request.url
                  // A second application would append the marker to the rewritten URL.
                  if (request.url.startsWith("custom://")) REWRITTEN_URL else "${request.url}?again"
                },
                headers = { request ->
                  headerUrls += request.url
                  emptyMap()
                },
              )
          )
      )
    fixture.use {
      it.session.setBaseStyle(BaseStyle.Uri("custom://style.json"))
      it.pumpUntil("the rewritten load to fail") { it.errors.isNotEmpty() }
    }
    assertTrue(
      rewritten.toList().any { it.startsWith("custom://") },
      "the interceptor never saw the custom scheme: $rewritten",
    )
    assertTrue(
      rewritten.all { it.startsWith("custom://") },
      "a hook received an already-rewritten URL: $rewritten",
    )
    assertContains(headerUrls.toList(), REWRITTEN_URL)
    assertTrue(
      headerUrls.none { it.endsWith("?again") },
      "the rewrite was applied twice: $headerUrls",
    )
  }

  private companion object {
    const val REWRITTEN_URL = "https://example.invalid/style.json"
  }
}
