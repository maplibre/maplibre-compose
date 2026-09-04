package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class StyleTransitionTest {
  @Test
  fun a_style_without_a_transition_reads_the_spec_defaults(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(EMPTY_STYLE)

      assertEquals(TransitionOptions(), fixture.state.style.transition.get())
    }
  }

  @Test
  fun a_declared_transition_reads_back_and_a_reload_replaces_a_write(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(TIMED_STYLE)
      val declared = TransitionOptions(duration = 500.milliseconds, delay = 100.milliseconds)
      assertEquals(declared, fixture.state.style.transition.get())

      val written = TransitionOptions(duration = 1.seconds, delay = 50.milliseconds)
      fixture.state.style.transition.set(written)
      assertEquals(written, fixture.state.style.transition.get())

      fixture.loadStyle(EMPTY_STYLE)
      assertEquals(TransitionOptions(), fixture.state.style.transition.get())
    }
  }

  private companion object {
    val EMPTY_STYLE = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    val TIMED_STYLE =
      BaseStyle.Json(
        """{"version":8,"transition":{"duration":500,"delay":100},"sources":{},"layers":[]}"""
      )
  }
}
