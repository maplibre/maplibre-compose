package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.desktop.HeadlessMapFixture
import org.maplibre.compose.style.BaseStyle

/**
 * Proves a map that has finished its work stops asking to be drawn. Desktop frames are requested
 * rather than continuous, so a map nobody is touching should draw nothing at all. The measurement
 * only holds under [HeadlessMapFixture.renderOnDemand], which draws only when the session asks.
 */
class DesktopMapIdleTest {

  @Test
  fun `a settled empty map asks for no further frames`() {
    HeadlessMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.pumpUntilRendered()
      fixture.settle()

      val drawn = fixture.renderOnDemand(IDLE_WINDOW)
      assertTrue(
        drawn <= IDLE_FRAME_ALLOWANCE,
        "A settled map drew $drawn frames across $IDLE_WINDOW with nothing asking it to.",
      )
    }
  }

  /**
   * mbgl advances a camera transition from `onDidFinishRenderingFrame`, so it drives frames on its
   * own; one that ends without clearing that request leaves the map drawing forever.
   */
  @Test
  fun `a map asks for no further frames once its camera stops`() {
    HeadlessMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.pumpUntilRendered()
      fixture.session.setCameraPosition(
        fixture.session.getCameraPosition().copy(zoom = 4.0, bearing = 30.0)
      )
      fixture.settle()

      val drawn = fixture.renderOnDemand(IDLE_WINDOW)
      assertTrue(
        drawn <= IDLE_FRAME_ALLOWANCE,
        "A map drew $drawn frames across $IDLE_WINDOW after its camera came to rest.",
      )
    }
  }

  private companion object {
    /** How long the map is watched for after it has settled. */
    val IDLE_WINDOW: Duration = 3.seconds

    /**
     * Frames a settled map may still draw across the window. Not zero, because settling is observed
     * rather than announced: work in flight when the quiet period elapses can still land a frame.
     */
    const val IDLE_FRAME_ALLOWANCE = 2
  }
}
