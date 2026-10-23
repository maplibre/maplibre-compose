package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.gljs.isNear
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class GlJsMapSessionTest {
  @Test
  fun camera_listeners_run_after_jump_to_and_a_nested_camera_wins() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val session = fixture.session as GlJsMapSession
      val engine = requireNotNull(session.engineMapForTest())
      val original = engine.asDynamic().jumpTo
      var insideJump = false
      val wrap =
        js(
          """(function(original, entering) {
        return function(options) {
          entering(true);
          try { return original.call(this, options); }
          finally { entering(false); }
        };
      })""",
        )
      engine.asDynamic().jumpTo = wrap(original) { entered: Boolean -> insideJump = entered }
      val callbacks = session.callbacks
      val newest = CameraPosition(zoom = 5.0, bearing = 70.0)
      var replaced = false
      session.callbacks =
        object : MapAdapter.Callbacks by callbacks {
          override fun onEvent(map: MapAdapter, event: MapEvent) {
            assertFalse(
              insideJump,
              "an engine listener must not re-enter Compose during jumpTo",
            )
            callbacks.onEvent(map, event)
            if (event is MapEvent.CameraMoveStarted && !replaced) {
              replaced = true
              fixture.state.setCameraPosition(newest)
            }
          }
        }
      try {
        fixture.state.setCameraPosition(CameraPosition(zoom = 3.0))
        assertTrue(replaced)
        assertTrue(fixture.state.cameraPosition.isNear(newest))
        assertTrue(session.getCameraPosition().isNear(newest))
      } finally {
        session.callbacks = callbacks
        engine.asDynamic().jumpTo = original
      }
    }
  }

  @Test
  fun closing_destroys_the_web_map_synchronously() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val session = fixture.session as GlJsMapSession
      assertTrue(session.engineMapForTest() != null)
      session.close()
      assertNull(session.engineMapForTest())
      session.awaitClosed()
    }
  }
}
