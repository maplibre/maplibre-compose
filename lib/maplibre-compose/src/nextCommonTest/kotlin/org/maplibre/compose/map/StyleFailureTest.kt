package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class StyleFailureTest {

  @Test
  fun a_malformed_inline_style_is_reported_rather_than_thrown(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setBaseStyle(BaseStyle.Json("{ this is not json"))

      // Both platforms parse an inline style synchronously, inside the host's draw pass.
      it.pump(frames = 10)

      assertTrue(
        it.errors.any { error -> error.startsWith("mapFailLoading") },
        "Expected a load failure to be reported. Errors: ${it.errors}, events: ${it.events}",
      )
    }
  }

  @Test
  fun a_failed_style_is_reported_once(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setBaseStyle(BaseStyle.Json("{ this is not json"))
      it.pump(frames = 10)
      val reported = it.errors.size
      it.pump(frames = 20)

      assertEquals(reported, it.errors.size, "The style was retried: ${it.errors}")
    }
  }
}
