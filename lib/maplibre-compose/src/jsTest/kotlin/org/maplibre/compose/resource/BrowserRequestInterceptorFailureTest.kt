package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.gljs.DefaultWorkerUrl
import org.maplibre.compose.gljs.GlJsRuntime
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.GlJsMapFixture
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.runMapTest

/** A request whose interceptor throws fails instead of going out without the interceptor. */
class BrowserRequestInterceptorFailureTest {

  @Test
  fun a_throwing_rewrite_fails_the_style_without_fetching_it(): MapTestResult = runMapTest {
    assertStyleFailsWithoutFetching(
      MapRequestInterceptor(rewriteUrl = { error("token store exploded") })
    )
  }

  @Test
  fun throwing_headers_fail_the_style_without_fetching_it(): MapTestResult = runMapTest {
    assertStyleFailsWithoutFetching(
      MapRequestInterceptor(headers = { error("token store exploded") })
    )
  }

  private suspend fun assertStyleFailsWithoutFetching(interceptor: MapRequestInterceptor) {
    GlJsRuntime.pointAtWorker(DefaultWorkerUrl)
    val requests = GlJsRequestController(MapResourceConfig(interceptor = interceptor))
    try {
      GlJsMapFixture(MapFixture.DefaultExtent, requests).use { fixture ->
        fixture.session.setBaseStyle(BaseStyle.Uri(StyleUrl))
        fixture.pumpUntil("the style to fail") { fixture.errors.isNotEmpty() }
        // A fetch of the unresolvable host would fail with a network error instead.
        assertTrue(
          fixture.errors.any { "request interceptor failed" in it },
          "the style did not fail on the interceptor: ${fixture.errors}",
        )
      }
    } finally {
      requests.close()
    }
  }

  private companion object {
    const val StyleUrl = "https://example.invalid/style.json"
  }
}
