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

  @Test
  fun a_scale_of_one_returns_the_same_timing() {
    val options = TransitionOptions(700.milliseconds, 50.milliseconds)
    assertEquals(options, options.scaledBy(1f))
  }

  @Test
  fun scaling_multiplies_duration_and_delay() {
    assertEquals(
      TransitionOptions(350.milliseconds, 25.milliseconds),
      TransitionOptions(700.milliseconds, 50.milliseconds).scaledBy(0.5f),
    )
  }

  /** A zero scale is Android's "remove animations": property changes apply instantly. */
  @Test
  fun a_scale_of_zero_zeroes_duration_and_delay() {
    assertEquals(
      TransitionOptions(0.milliseconds, 0.milliseconds),
      TransitionOptions(700.milliseconds, 50.milliseconds).scaledBy(0f),
    )
  }
}
