package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle

/** A request whose interceptor throws fails instead of going out without the interceptor. */
class MlnFfiRequestInterceptorFailureTest {

  @Test
  fun a_throwing_rewrite_fails_the_style_without_fetching_it() {
    assertStyleFailsWithoutFetching(
      MapRequestInterceptor(rewriteUrl = { error("token store exploded") })
    )
  }

  @Test
  fun throwing_headers_fail_the_style_without_fetching_it() {
    assertStyleFailsWithoutFetching(
      MapRequestInterceptor(headers = { error("token store exploded") })
    )
  }

  private fun assertStyleFailsWithoutFetching(interceptor: MapRequestInterceptor) {
    val fixture =
      BridgeMapFixture.create(resourceConfig = MapResourceConfig(interceptor = interceptor))
    val errors = fixture.use {
      it.session.setBaseStyle(BaseStyle.Uri(StyleUrl))
      it.pumpUntil("the style to fail") { it.errors.isNotEmpty() }
      it.errors.toList()
    }
    // A fetch of the unresolvable host would fail with a transport error instead.
    assertTrue(
      errors.any { "request interceptor failed" in it },
      "the style did not fail on the interceptor: $errors",
    )
  }

  private companion object {
    const val StyleUrl = "https://example.invalid/style.json"
  }
}
