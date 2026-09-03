package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json

class TransitionJsonTest {

  @Test
  fun a_transition_object_reads_back_as_the_timing_it_states() {
    assertEquals(
      TransitionOptions(700.milliseconds, 50.milliseconds),
      transitionOf("""{"duration":700,"delay":50}"""),
    )
  }

  /**
   * An engine times a field that a transition object omits with the style's global transition, and
   * a base style is free to write such an object, so a partial object must report no timing rather
   * than a substituted one.
   */
  @Test
  fun an_object_without_two_usable_fields_reads_back_as_no_transition() {
    assertNull(transitionOf("""{"duration":700}"""))
    assertNull(transitionOf("""{"delay":50}"""))
    assertNull(transitionOf("{}"))
    assertNull(transitionOf("""{"duration":700,"delay":-50}"""))
  }

  private fun transitionOf(json: String): TransitionOptions? =
    Json.parseToJsonElement(json).toTransitionOptions()
}
