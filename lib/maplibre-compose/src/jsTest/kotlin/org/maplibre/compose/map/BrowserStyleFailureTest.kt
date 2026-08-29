package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.GlJsMapFixture
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class BrowserStyleFailureTest {
  @Test
  fun a_source_error_does_not_cancel_a_pending_style(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Json(STYLE_A))
      fixture.events.clear()
      fixture.errors.clear()

      fixture.session.setBaseStyle(BaseStyle.Json(STYLE_B))
      (fixture as GlJsMapFixture).fireStyleError("unrelated source failure")
      fixture.session.setBaseStyle(BaseStyle.Json(STYLE_B))
      fixture.pumpUntil("the replacement style to load", timeout = 5.seconds) {
        fixture.events.contains(MapFixture.STYLE_LOADED)
      }

      assertTrue(fixture.errors.isEmpty(), "The source error failed the style: ${fixture.errors}")
    }
  }

  @Test
  fun a_missing_style_url_is_reported_once(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.session.setBaseStyle(BaseStyle.Uri("/missing-maplibre-compose-style.json"))
      fixture.pumpUntil("the missing style URL to fail") { fixture.errors.isNotEmpty() }

      val reported = fixture.errors.single()
      fixture.pump(frames = 20)
      assertEquals(listOf(reported), fixture.errors)
    }
  }

  @Test
  fun an_invalid_inline_style_is_reported_once(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.session.setBaseStyle(BaseStyle.Json(INVALID_STYLE))
      fixture.pumpUntil("the invalid style to fail") { fixture.errors.isNotEmpty() }

      val reported = fixture.errors.single()
      fixture.pump(frames = 20)
      assertEquals(listOf(reported), fixture.errors)
    }
  }

  private companion object {
    const val STYLE_A = """{"version":8,"sources":{},"layers":[]}"""
    const val STYLE_B = """{"version":8,"name":"replacement","sources":{},"layers":[]}"""
    const val INVALID_STYLE = """{"version":7,"sources":{},"layers":[]}"""
  }
}
