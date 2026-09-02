package org.maplibre.compose.resource

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RecordingList

@OptIn(ExperimentalAtomicApi::class)
class MlnFfiRequestInterceptorCustomSchemeTest {

  @Test
  fun an_interceptor_rewrites_a_custom_scheme_style_url() {
    val seen = RecordingList<String>()
    val interceptorRan = AtomicBoolean(false)
    val fixture =
      BridgeMapFixture.create(
        resourceConfig =
          MapResourceConfig(
            interceptor = { request ->
              interceptorRan.store(true)
              seen += request.url
              if (request.url.startsWith("custom://")) {
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
    assertTrue(interceptorRan.load(), "the interceptor was not consulted for custom://style.json")
    assertTrue(
      seen.toList().any { it.startsWith("custom://") },
      "the interceptor never saw the custom scheme: $seen",
    )
  }
}
