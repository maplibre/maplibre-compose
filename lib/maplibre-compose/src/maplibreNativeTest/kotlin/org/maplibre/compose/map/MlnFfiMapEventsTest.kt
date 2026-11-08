package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.nativeffi.CameraChangeMode
import org.maplibre.nativeffi.CameraChangeSource
import org.maplibre.nativeffi.RenderMode
import org.maplibre.nativeffi.RenderingStats
import org.maplibre.nativeffi.RuntimeEvent
import org.maplibre.nativeffi.RuntimeEventCameraChange
import org.maplibre.nativeffi.RuntimeEventPayload
import org.maplibre.nativeffi.RuntimeEventRenderFrame
import org.maplibre.nativeffi.RuntimeEventSourceType
import org.maplibre.nativeffi.RuntimeEventType

class MlnFfiMapEventsTest {

  @Test
  fun a_finished_frame_carries_the_render_stats() {
    val event =
      runtimeEvent(
          RuntimeEventType.MAP_RENDER_FRAME_FINISHED,
          payload =
            RuntimeEventPayload.RenderFrame(
              RuntimeEventRenderFrame(
                mode = RenderMode.FULL,
                needsRepaint = true,
                placementChanged = false,
                stats =
                  RenderingStats(
                    encodingTimeMs = 1.5,
                    renderingTimeMs = 2.5,
                    frameCount = 3L,
                    drawCallCount = 4L,
                    totalDrawCallCount = 5L,
                  ),
              ),
            ),
        )
        .toMapEvent()

    val stats = (event as MapEvent.FrameRendered).stats!!
    assertEquals(RenderStats.Mode.Full, stats.mode)
    assertTrue(stats.needsRepaint)
    assertTrue(!stats.placementChanged)
    assertEquals(1.5.milliseconds, stats.encodingTime)
    assertEquals(2.5.milliseconds, stats.renderingTime)
    assertEquals(3L, stats.frameCount)
    assertEquals(4L, stats.drawCallCount)
    assertEquals(5L, stats.totalDrawCallCount)
  }

  @Test
  fun a_camera_change_reads_its_mode_from_the_payload() {
    fun change(mode: CameraChangeMode) =
      runtimeEvent(
          RuntimeEventType.MAP_CAMERA_WILL_CHANGE,
          payload =
            RuntimeEventPayload.CameraChange(
              RuntimeEventCameraChange(mode = mode, source = CameraChangeSource.COMMAND),
            ),
        )
        .toMapEvent()

    assertEquals(MapEvent.CameraMoveStarted(true), change(CameraChangeMode.ANIMATED))
    assertEquals(MapEvent.CameraMoveStarted(false), change(CameraChangeMode.IMMEDIATE))
  }

  @Test
  fun a_partial_frame_reports_its_mode() {
    assertEquals(RenderStats.Mode.Partial, frameStats(RenderMode.PARTIAL).mode)
  }

  @Test
  fun a_frame_in_an_unnamed_render_mode_keeps_its_number() {
    assertEquals("99", frameStats(RenderMode(99u)).mode.value)
  }

  @Test
  fun an_event_outside_the_catalog_translates_to_nothing() {
    assertNull(runtimeEvent(RuntimeEventType.MAP_RENDER_ERROR).toMapEvent())
    assertNull(runtimeEvent(RuntimeEventType.MAP_STYLE_LOADED).toMapEvent())
    assertNull(runtimeEvent(RuntimeEventType.MAP_STYLE_IMAGE_MISSING).toMapEvent())
    assertNull(runtimeEvent(RuntimeEventType(9999u)).toMapEvent())
  }

  private fun frameStats(mode: RenderMode): RenderStats {
    val event =
      runtimeEvent(
        RuntimeEventType.MAP_RENDER_FRAME_FINISHED,
        payload =
          RuntimeEventPayload.RenderFrame(
            RuntimeEventRenderFrame(
              mode = mode,
              needsRepaint = false,
              placementChanged = false,
              stats = RenderingStats(0.0, 0.0, 0L, 0L, 0L),
            ),
          ),
      )
    return assertNotNull((event.toMapEvent() as MapEvent.FrameRendered).stats)
  }

  private fun runtimeEvent(
    type: RuntimeEventType,
    payload: RuntimeEventPayload = RuntimeEventPayload.None,
  ) =
    RuntimeEvent(
      type = type,
      source = 1uL,
      generation = 1uL,
      sourceType = RuntimeEventSourceType.MAP,
      payload = payload,
      message = "",
    )
}
