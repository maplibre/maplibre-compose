package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.desktop.HeadlessMapFixture
import org.maplibre.compose.style.BaseStyle

/**
 * A style that fails to load must be reported, not thrown out of the frame, since applying a style
 * happens inside the host's draw pass.
 *
 * The two setters report differently: `setStyleJson` parses inline, so a malformed style throws
 * from the setter *and* queues a loading-failed event, while `setStyleUrl` only starts a fetch and
 * reports through the event alone.
 */
class DesktopStyleFailureTest {

  @Test
  fun `a malformed inline style is reported rather than thrown`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.session.setBaseStyle(BaseStyle.Json("{ this is not json"))

      // Would throw out of render() before the guard: setStyleJson fails synchronously.
      it.pump(frames = 10)

      assertTrue(
        it.errors.any { error -> error.startsWith("mapFailLoading") },
        "Expected a load failure to be reported. Errors: ${it.errors}, events: ${it.events}",
      )
    }
  }

  /** The same contract for the fetching setter, which reports only through the event. */
  @Test
  fun `an unreachable style url is reported rather than thrown`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.session.setBaseStyle(BaseStyle.Uri("https://example.invalid/style.json"))

      it.pumpUntil("the load to fail") {
        it.errors.any { error -> error.startsWith("mapFailLoading") }
      }
    }
  }

  @Test
  fun `a failed style is not retried on every frame`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.session.setBaseStyle(BaseStyle.Json("{ this is not json"))
      it.pump(frames = 10)
      val after = it.errors.size
      it.pump(frames = 20)

      assertTrue(
        it.errors.size == after,
        "The style was retried: ${it.errors.size - after} further failures across 20 frames",
      )
    }
  }
}
