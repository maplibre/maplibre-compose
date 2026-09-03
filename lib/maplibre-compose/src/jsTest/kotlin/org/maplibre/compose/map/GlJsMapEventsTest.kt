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

  @Test
  fun a_missing_image_carries_its_id() {
    assertEquals(
      MapEvent.StyleImageMissing("missing-icon"),
      translate("styleimagemissing", eventWithId("missing-icon")),
    )
  }

  private fun translate(type: String, event: GlJsMapEvent = unsafeJso()) =
    (ENGINE_GL_JS_EVENTS + STYLE_GL_JS_EVENTS + PRESENTATION_GL_JS_EVENTS).getValue(type)(event)

  private fun eventWithId(id: String): GlJsMapEvent =
    unsafeJso<GlJsMapEvent>().also { it.asDynamic().id = id }
}
