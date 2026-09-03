package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RecordingList

class MlnFfiRequestInterceptorCustomSchemeTest {

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
