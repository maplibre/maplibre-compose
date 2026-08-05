package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.desktop.HeadlessMapFixture
import org.maplibre.compose.style.BaseStyle

/**
 * Proves a map that has finished its work stops asking to be drawn.
 *
 * Desktop frames are requested rather than continuous, so a map nobody is touching should draw
 * nothing at all. `DesktopMapRepaintTest` covers the opposite failure — a change that draws no
 * frame and so is never seen — and the two together pin the frame loop from both sides: every
 * change draws, and nothing else does.
 *
 * This is worth a test rather than an inspection because the cost is invisible. A map repainting
 * steadily on a still screen looks exactly like an idle one; it shows up as a warm laptop and a
 * flat battery, not as anything on screen. It is also easy to reintroduce from a long way away,
 * since anything that touches the map from a Compose frame will do it.
 *
 * The measurement only means something under [HeadlessMapFixture.renderOnDemand], which draws when
 * the session asks and not otherwise. A loop that draws on a clock instead hands the map frames it
 * never requested, and because a rendered frame is itself something MapLibre responds to, that loop
 * sustains itself and reads as a map that will not settle — a measurement of the loop, not the map.
 */
class DesktopMapIdleTest {

  /** The floor: nothing composed into the map at all. */
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
   * A map that has been moved and then left alone.
   *
   * The camera is the interesting case, because a transition is the one thing that legitimately
   * drives frames on its own: mbgl advances it from `onDidFinishRenderingFrame`, so it needs
   * rendered frames to progress. A transition that finishes without clearing whatever is asking for
   * those frames leaves the map drawing forever, and the map looks perfectly still while it does.
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
     * Frames a settled map may still draw across the window.
     *
     * Not zero, because settling is observed rather than announced: the map is called settled once
     * a quiet period passes with nothing requested, and work already in flight at that moment can
     * still land a frame afterwards. A repainting map is nowhere near this bound — the behaviour
     * this guards against runs to tens of frames a second — so the allowance costs no sensitivity.
     */
    const val IDLE_FRAME_ALLOWANCE = 2
  }
}
