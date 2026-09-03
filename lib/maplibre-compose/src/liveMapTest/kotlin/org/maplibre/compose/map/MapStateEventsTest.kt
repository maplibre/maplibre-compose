package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MISSING_ICON_ID
import org.maplibre.compose.testing.MISSING_ICON_STYLE
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

/** [MapState.events] publishes the events that both engines report. */
class MapStateEventsTest {

  @Test
  fun a_loaded_rendering_map_publishes_a_style_load_and_a_frame(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.awaitWhileRendering("the style load and a frame to be published") {
        coroutineScope {
          // Subscribed before the style is set, because the flow replays nothing.
          val styleLoaded =
            async(start = CoroutineStart.UNDISPATCHED) {
              fixture.state.events.first { it is MapEvent.StyleLoaded }
            }
          fixture.session.setBaseStyle(BaseStyle.Empty)

          assertEquals(MapEvent.StyleLoaded, styleLoaded.await())
          fixture.state.events.first { it is MapEvent.FrameRendered }
        }
      }
    }
  }

  /** The recipe the documentation publishes for a style that references a missing icon. */
  @Test
  fun an_image_added_for_a_published_miss_reaches_the_style(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      coroutineScope {
        // Subscribed before the style is set, because the flow replays nothing.
        val missing =
          async(start = CoroutineStart.UNDISPATCHED) {
            fixture.state.events.filterIsInstance<MapEvent.StyleImageMissing>().first()
          }
        fixture.loadStyle(BaseStyle.Json(MISSING_ICON_STYLE))
        fixture.pumpUntil("the missing icon to be published", timeout = 20.seconds) {
          missing.isCompleted
        }

        val imageId = missing.await().imageId
        assertEquals(MISSING_ICON_ID, imageId)
        fixture.state.style.images.add(imageId, ImageBitmap(1, 1))
        fixture.settle()

        assertTrue(fixture.state.style.images.remove(imageId))
      }
    }
  }
}
