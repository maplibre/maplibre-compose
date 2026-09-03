package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.maplibre.nativeffi.map.RenderingStats
import org.maplibre.nativeffi.render.RenderMode
import org.maplibre.nativeffi.runtime.CameraChangeMode
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeEventSourceType
import org.maplibre.nativeffi.runtime.RuntimeEventType

class MlnFfiMapEventsTest {

  @Test
  fun a_loaded_style_translates() {
    assertEquals(
      MapEvent.StyleLoaded,
      runtimeEvent(RuntimeEventType.MAP_STYLE_LOADED).toMapEvent(),
    )
  }

  @Test
  fun a_load_failure_carries_the_engine_message() {
    assertEquals(
      MapEvent.StyleLoadFailed("no route to host"),
      runtimeEvent(RuntimeEventType.MAP_LOADING_FAILED, message = "no route to host").toMapEvent(),
    )
  }

  @Test
  fun a_blank_load_failure_carries_a_stated_reason() {
    val event = runtimeEvent(RuntimeEventType.MAP_LOADING_FAILED).toMapEvent()
    assertTrue((event as MapEvent.StyleLoadFailed).reason.isNotBlank())
  }

  @Test
  fun an_idle_translates() {
    assertEquals(MapEvent.Idle, runtimeEvent(RuntimeEventType.MAP_IDLE).toMapEvent())
  }

  @Test
  fun a_camera_change_reports_whether_it_is_animated() {
    assertEquals(
      MapEvent.CameraMoveStarted(animated = true),
      runtimeEvent(
          RuntimeEventType.MAP_CAMERA_WILL_CHANGE,
          code = CameraChangeMode.ANIMATED.nativeValue,
        )
        .toMapEvent(),
    )
    assertEquals(
      MapEvent.CameraMoveEnded(animated = false),
      runtimeEvent(
          RuntimeEventType.MAP_CAMERA_DID_CHANGE,
          code = CameraChangeMode.IMMEDIATE.nativeValue,
        )
        .toMapEvent(),
    )
    assertEquals(
      MapEvent.CameraMoved,
      runtimeEvent(RuntimeEventType.MAP_CAMERA_IS_CHANGING).toMapEvent(),
    )
  }

  @Test
  fun a_finished_frame_carries_the_render_stats() {
    val event =
      runtimeEvent(
          RuntimeEventType.MAP_RENDER_FRAME_FINISHED,
          payload =
            RuntimeEventPayload.RenderFrame(
              mode = RenderMode.FULL,
              needsRepaint = true,
              placementChanged = false,
              stats =
                RenderingStats(
                  encodingTime = 1.5,
                  renderingTime = 2.5,
                  frameCount = 3L,
                  drawCallCount = 4L,
                  totalDrawCallCount = 5L,
                ),
            ),
        )
        .toMapEvent()

    val stats = (event as MapEvent.FrameRendered).stats!!
    assertEquals(RenderStats.Mode.Full, stats.mode)
    assertTrue(stats.needsRepaint)
    assertTrue(!stats.placementChanged)
    assertEquals(1.5.seconds, stats.encodingTime)
    assertEquals(2.5.seconds, stats.renderingTime)
    assertEquals(3L, stats.frameCount)
    assertEquals(4L, stats.drawCallCount)
    assertEquals(5L, stats.totalDrawCallCount)
  }

  @Test
  fun a_partial_frame_reports_its_mode() {
    assertEquals(RenderStats.Mode.Partial, frameStats(RenderMode.PARTIAL)?.mode)
  }

  @Test
  fun a_frame_in_an_unnamed_render_mode_reports_no_mode() {
    assertNull(frameStats(RenderMode(99))?.mode)
  }

  @Test
  fun a_missing_image_carries_its_id() {
    assertEquals(
      MapEvent.StyleImageMissing("missing-icon"),
      runtimeEvent(RuntimeEventType.MAP_STYLE_IMAGE_MISSING, message = "missing-icon").toMapEvent(),
    )
  }

  @Test
  fun an_event_outside_the_catalog_translates_to_nothing() {
    assertNull(runtimeEvent(RuntimeEventType.MAP_RENDER_ERROR).toMapEvent())
    assertNull(runtimeEvent(RuntimeEventType(9999)).toMapEvent())
  }

  private fun frameStats(mode: RenderMode): RenderStats? {
    val event =
      runtimeEvent(
        RuntimeEventType.MAP_RENDER_FRAME_FINISHED,
        payload =
          RuntimeEventPayload.RenderFrame(
            mode = mode,
            needsRepaint = false,
            placementChanged = false,
            stats = RenderingStats(0.0, 0.0, 0L, 0L, 0L),
          ),
      )
    return (event.toMapEvent() as MapEvent.FrameRendered).stats
  }

  private fun runtimeEvent(
    type: RuntimeEventType,
    code: Int = 0,
    payload: RuntimeEventPayload = RuntimeEventPayload.None,
    message: String = "",
  ) =
    RuntimeEvent(
      type = type,
      sourceType = RuntimeEventSourceType.MAP,
      sourceId = 0L,
      runtimeSource = null,
      mapSource = null,
      code = code,
      payload = payload,
      message = message,
    )
}
