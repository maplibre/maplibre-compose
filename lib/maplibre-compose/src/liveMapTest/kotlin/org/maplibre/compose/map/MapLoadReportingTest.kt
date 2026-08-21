package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class MapLoadReportingTest {

  @Test
  fun every_style_reports_that_it_finished_loading(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fun loads() = fixture.events.count { it == MapFixture.LOAD_FINISHED }

      fixture.loadStyle(FIRST)
      fixture.pumpUntil("the first style to finish loading") { loads() == 1 }

      fixture.events.clear()
      fixture.loadStyle(SECOND)
      fixture.pumpUntil("the second style to finish loading") { loads() == 1 }

      fixture.settle()
      assertEquals(1, loads(), "a style should finish loading exactly once")
    }
  }

  @Test
  fun rapid_style_changes_leave_the_latest_style_active(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(FIRST)

      fixture.session.setBaseStyle(SECOND)
      fixture.session.setBaseStyle(THIRD)

      fixture.pumpUntil("the third style to become active") {
        fixture.style?.getLayer("third") != null
      }
      fixture.settle()

      assertEquals(emptyList(), fixture.errors)
      assertNotNull(fixture.style?.getLayer("third"))
      assertNull(fixture.style?.getLayer("bg"))
    }
  }

  private companion object {
    val FIRST = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")

    val SECOND =
      BaseStyle.Json("""{"version":8,"sources":{},"layers":[{"id":"bg","type":"background"}]}""")

    val THIRD =
      BaseStyle.Json("""{"version":8,"sources":{},"layers":[{"id":"third","type":"background"}]}""")
  }
}
