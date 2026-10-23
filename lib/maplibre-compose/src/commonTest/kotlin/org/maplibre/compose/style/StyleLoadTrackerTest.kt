package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StyleLoadTrackerTest {
  @Test
  fun initial_presentation_waits_for_both_prerequisites_in_either_order() {
    for (contentFirst in listOf(false, true)) {
      val tracker = StyleLoadTracker()
      val identity = StyleIdentity.create()
      assertTrue(tracker.loaded(tracker.requestId, identity, baseStyleReady = false))
      assertFalse(
        if (contentFirst) tracker.reconciled(identity) else tracker.baseStyleReady(identity),
      )
      assertEquals(StylePresentation.Hidden, tracker.presentation)
      assertFalse(tracker.isReady)

      assertTrue(
        if (contentFirst) tracker.baseStyleReady(identity) else tracker.reconciled(identity),
      )
      assertEquals(StylePresentation.Live, tracker.presentation)
      assertTrue(tracker.isReady)
      assertFalse(tracker.baseStyleReady(identity))
      assertFalse(
        tracker.reconciled(identity),
        "readiness is reported only once per initialization",
      )
    }
  }

  @Test
  fun replacement_retains_the_frame_until_content_is_complete_but_allows_engine_progress() {
    val tracker = StyleLoadTracker()
    val first = StyleIdentity.create()
    assertTrue(tracker.loaded(tracker.requestId, first))
    assertTrue(tracker.reconciled(first))

    val replacement = tracker.request()
    assertEquals(StylePresentation.Retained, tracker.presentation)
    val second = StyleIdentity.create()
    assertTrue(tracker.loaded(replacement, second, baseStyleReady = false))
    assertEquals(StylePresentation.Retained, tracker.presentation)
    assertFalse(tracker.reconciled(second))
    assertEquals(StylePresentation.Live, tracker.presentation, "source loading may need frames")
    assertFalse(tracker.isReady)
    assertTrue(tracker.baseStyleReady(second))

    assertFalse(tracker.reconciled(second), "ordinary updates do not repeat readiness")
    assertEquals(StylePresentation.Live, tracker.presentation)
  }

  @Test
  fun failed_reconciliation_can_recover_with_a_new_revision() {
    val tracker = StyleLoadTracker()
    val identity = StyleIdentity.create()
    assertTrue(tracker.loaded(tracker.requestId, identity))
    assertTrue(tracker.reconciled(identity))
    tracker.failed(identity)
    assertEquals(StylePresentation.Hidden, tracker.presentation)
    assertFalse(tracker.isReady)
    assertTrue(tracker.reconciled(identity))
    assertEquals(StylePresentation.Live, tracker.presentation)
  }

  @Test
  fun a_new_presentation_or_engine_waits_for_current_content() {
    val tracker = StyleLoadTracker()
    val identity = StyleIdentity.create()
    assertTrue(tracker.loaded(tracker.requestId, identity))
    assertTrue(tracker.reconciled(identity))
    tracker.resetPresentation()
    assertEquals(StylePresentation.Hidden, tracker.presentation)
    assertEquals(
      StylePresentation.Hidden,
      tracker.presentation,
      "a new surface waits for current content",
    )
    assertTrue(tracker.reconciled(identity))
    assertEquals(StylePresentation.Live, tracker.presentation)

    tracker.engineBecameUnavailable()
    assertEquals(StylePresentation.Hidden, tracker.presentation)
  }
}
