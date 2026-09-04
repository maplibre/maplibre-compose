package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class MlnFfiPlacementTransitionsTest {
  @Test
  fun placement_transitions_switch_independently_of_the_transition(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}"""))
      val transition = fixture.state.style.transition
      assertEquals(true, transition.placementTransitions())

      transition.setPlacementTransitions(false)
      assertEquals(false, transition.placementTransitions())

      transition.set(TransitionOptions(duration = 10.milliseconds))
      assertEquals(false, transition.placementTransitions())
      assertEquals(TransitionOptions(duration = 10.milliseconds), transition.get())
    }
  }
}
