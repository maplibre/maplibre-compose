package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class StyleFailureTest {

  @Test
  fun a_malformed_inline_style_is_reported_once(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setBaseStyle(BaseStyle.Json("{ this is not json"))
      it.pumpUntil("the load to fail") {
        it.errors.any { error -> error.startsWith("mapFailLoading") }
      }

      val reported = it.errors.size
      it.pump(frames = 20)
      assertEquals(reported, it.errors.size, "The style was retried: ${it.errors}")
    }
  }
}
