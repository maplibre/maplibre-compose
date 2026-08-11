package org.maplibre.compose.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Position

class BrowserCameraTransitionLifecycleTest {

  @Test
  fun cancelling_transitions_releases_an_animation_queued_before_the_first_style(): MapTestResult =
    runMapTest {
      createMapFixture().use {
        it.session.setBaseStyle(BaseStyle.Empty)
        val animation =
          CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            it.session.animateCameraPosition(TARGET, 60.seconds)
          }

        assertFalse(animation.isCompleted, "the animation should be queued before cancellation")
        it.gestures.cancelTransitions()
        it.pumpUntil("transition cancellation to release the queued animation") {
          animation.isCompleted
        }
        it.loadStyle(BaseStyle.Empty)

        assertFalse(animation.isCancelled, "transition cancellation should resume the waiter")
        assertTrue(
          abs(it.session.getCameraPosition().zoom) < 0.01,
          "the cancelled animation should not start after the style loads",
        )
      }
    }

  @Test
  fun a_failed_initial_style_resumes_a_queued_animation(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.session.setBaseStyle(BaseStyle.Json("{ this is not json"))
      val animation =
        CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
          it.session.animateCameraPosition(
            TARGET,
            60.seconds,
          )
        }

      assertFalse(
        animation.isCompleted,
        "the animation should wait for the initial style result",
      )
      it.pumpUntil("the failed style to release the queued animation") {
        it.errors.isNotEmpty() && animation.isCompleted
      }

      assertFalse(animation.isCancelled, "a style failure should resume the waiter normally")
    }
  }

  private companion object {
    val TARGET = CameraPosition(target = Position(11.0, 47.0), zoom = 8.0)
  }
}
