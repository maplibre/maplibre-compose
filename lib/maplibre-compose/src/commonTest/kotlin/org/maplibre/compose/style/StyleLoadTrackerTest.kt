package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class StyleLoadTrackerTest {
  @Test
  fun initial_presentation_waits_for_both_prerequisites_in_either_order() {
    for (contentFirst in listOf(false, true)) {
      val tracker = StyleLoadTracker()
      val identity = StyleIdentity.create()
      assertTrue(tracker.loaded(tracker.requestId, identity, baseStyleReady = false))
      assertTrue(tracker.beginReconciliation(identity))
      assertFalse(
        if (contentFirst) tracker.reconciled(identity) else tracker.baseStyleReady(identity)
      )
      assertEquals(StylePresentation.Hidden, tracker.presentation)
      assertFalse(tracker.isReady)

      assertTrue(
        if (contentFirst) tracker.baseStyleReady(identity) else tracker.reconciled(identity)
      )
      assertEquals(StylePresentation.Live, tracker.presentation)
      assertTrue(tracker.isReady)
      assertFalse(tracker.baseStyleReady(identity))
      assertFalse(tracker.reconciled(identity), "readiness is reported only once per revision")
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
    assertTrue(tracker.beginReconciliation(second))
    assertEquals(StylePresentation.Retained, tracker.presentation)
    assertFalse(tracker.reconciled(second))
    assertEquals(StylePresentation.Live, tracker.presentation, "source loading may need frames")
    assertFalse(tracker.isReady)
    assertTrue(tracker.baseStyleReady(second))

    assertTrue(tracker.beginReconciliation(second))
    assertEquals(StylePresentation.Retained, tracker.presentation, "content updates are atomic too")
    assertTrue(tracker.reconciled(second))
    assertEquals(StylePresentation.Live, tracker.presentation)
  }

  @Test
  fun stale_completions_cannot_release_or_hide_the_current_presentation() {
    val tracker = StyleLoadTracker()
    val firstRequest = tracker.requestId
    val first = StyleIdentity.create()
    assertTrue(tracker.loaded(firstRequest, first))
    assertTrue(tracker.reconciled(first))
    val secondRequest = tracker.request()
    assertNotSame(firstRequest, secondRequest)
    assertFalse(tracker.loaded(firstRequest, StyleIdentity.create()))
    assertFalse(tracker.failed(firstRequest))
    assertFalse(tracker.reconciled(first))
    assertFalse(tracker.baseStyleReady(first))
    tracker.failed(first)
    assertEquals(StylePresentation.Retained, tracker.presentation)

    assertTrue(tracker.failed(secondRequest))
    assertEquals(StylePresentation.Hidden, tracker.presentation)
    assertFalse(
      tracker.loaded(secondRequest, StyleIdentity.create()),
      "failure is terminal for the request",
    )
    val third = StyleIdentity.create()
    assertTrue(tracker.loaded(tracker.request(), third))
    assertTrue(tracker.reconciled(third))
    assertEquals(StylePresentation.Live, tracker.presentation)
  }

  @Test
  fun failed_reconciliation_can_recover_with_a_new_revision() {
    val tracker = StyleLoadTracker()
    val identity = StyleIdentity.create()
    assertTrue(tracker.loaded(tracker.requestId, identity))
    assertTrue(tracker.reconciled(identity))
    assertTrue(tracker.beginReconciliation(identity))
    tracker.failed(identity)
    assertEquals(StylePresentation.Hidden, tracker.presentation)
    assertFalse(tracker.isReady)
    assertTrue(tracker.beginReconciliation(identity))
    assertTrue(tracker.reconciled(identity))
    assertEquals(StylePresentation.Live, tracker.presentation)
  }

  @Test
  fun new_presentations_replay_content_and_destroyed_engines_reject_old_results() {
    val tracker = StyleLoadTracker()
    val request = tracker.requestId
    val identity = StyleIdentity.create()
    assertTrue(tracker.loaded(request, identity))
    assertTrue(tracker.reconciled(identity))
    tracker.resetPresentation()
    assertEquals(StylePresentation.Hidden, tracker.presentation)
    assertTrue(tracker.beginReconciliation(identity))
    assertEquals(
      StylePresentation.Hidden,
      tracker.presentation,
      "replay alone cannot reveal a new surface",
    )
    assertTrue(tracker.reconciled(identity))
    assertEquals(StylePresentation.Live, tracker.presentation)

    tracker.engineBecameUnavailable()
    assertNotSame(request, tracker.requestId)
    assertEquals(StylePresentation.Hidden, tracker.presentation)
    assertFalse(tracker.beginReconciliation(identity))
    assertFalse(tracker.loaded(request, identity))
    assertFalse(tracker.reconciled(identity))
    assertFalse(tracker.failed(request))
  }
}
