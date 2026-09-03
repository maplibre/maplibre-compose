package org.maplibre.compose.resource

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RecordingList

@OptIn(ExperimentalAtomicApi::class)
class MlnFfiRequestInterceptorCustomSchemeTest {

  @Test
  fun an_interceptor_rewrites_a_custom_scheme_style_url() {
    val seen = RecordingList<String>()
    val customSchemeCalls = AtomicInt(0)
    val fixture =
      BridgeMapFixture.create(
        resourceConfig =
          MapResourceConfig(
            interceptor = { request ->
              seen += request.url
              if (request.url.startsWith("custom://")) {
                customSchemeCalls.incrementAndFetch()
                MapRequestTransform(url = "https://example.invalid/style.json")
              } else {
                MapRequestTransform()
              }
            }
          )
      )
    fixture.use {
      it.session.setBaseStyle(BaseStyle.Uri("custom://style.json"))
      it.pumpUntil("the rewritten load to fail") {
        it.errors.any { error -> error.startsWith("mapFailLoading") }
      }
    }
    assertEquals(1, customSchemeCalls.load(), "the interceptor must run once for the style request")
    assertTrue(
      seen.toList().any { it.startsWith("custom://") },
      "the interceptor never saw the custom scheme: $seen",
    )
  }
}
