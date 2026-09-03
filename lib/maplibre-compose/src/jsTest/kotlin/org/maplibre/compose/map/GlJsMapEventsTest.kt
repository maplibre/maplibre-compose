package org.maplibre.compose.map

import js.objects.unsafeJso
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.gljs.GlJsMapEvent

class GlJsMapEventsTest {

  @Test
  fun the_camera_events_translate_without_an_animation_flag() {
    assertEquals(MapEvent.CameraMoveStarted(animated = null), translate("movestart"))
    assertEquals(MapEvent.CameraMoved, translate("move"))
    assertEquals(MapEvent.CameraMoveEnded(animated = null), translate("moveend"))
  }

  @Test
  fun a_render_translates_without_stats() {
    assertEquals(MapEvent.FrameRendered(stats = null), translate("render"))
  }

  @Test
  fun an_idle_translates() {
    assertEquals(MapEvent.Idle, translate("idle"))
  }

  private fun translate(type: String, event: GlJsMapEvent = unsafeJso()) =
    (ENGINE_GL_JS_EVENTS + PRESENTATION_GL_JS_EVENTS).getValue(type)(event)
}
