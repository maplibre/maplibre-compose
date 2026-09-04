package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import org.maplibre.compose.style.BaseStyle
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
          // Both subscribe before the style is set, because the flow replays nothing and the map
          // stops asking for frames once it has drawn the loaded style.
          val styleLoaded =
            async(start = CoroutineStart.UNDISPATCHED) {
              fixture.state.events.first { it is MapEvent.StyleLoaded }
            }
          val frameRendered =
            async(start = CoroutineStart.UNDISPATCHED) {
              fixture.state.events.first { it is MapEvent.FrameRendered }
            }
          fixture.session.setBaseStyle(BaseStyle.Empty)

          assertEquals(MapEvent.StyleLoaded, styleLoaded.await())
          frameRendered.await()
        }
      }
    }
  }
}
