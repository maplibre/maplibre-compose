package org.maplibre.compose.map

import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.nativeffi.CameraChangeMode
import org.maplibre.nativeffi.RenderMode
import org.maplibre.nativeffi.RuntimeEvent
import org.maplibre.nativeffi.RuntimeEventPayload
import org.maplibre.nativeffi.RuntimeEventRenderFrame
import org.maplibre.nativeffi.RuntimeEventType

/**
 * Null for an event type that has no counterpart in the common catalog. Style loads report through
 * their command's completion instead.
 */
internal fun RuntimeEvent.toMapEvent(): MapEvent? =
  when (type) {
    RuntimeEventType.MAP_IDLE -> MapEvent.Idle
    RuntimeEventType.MAP_CAMERA_WILL_CHANGE -> MapEvent.CameraMoveStarted(isAnimatedChange())
    RuntimeEventType.MAP_CAMERA_IS_CHANGING -> MapEvent.CameraMoved
    RuntimeEventType.MAP_CAMERA_DID_CHANGE -> MapEvent.CameraMoveEnded(isAnimatedChange())
    RuntimeEventType.MAP_RENDER_FRAME_FINISHED ->
      MapEvent.FrameRendered((payload as? RuntimeEventPayload.RenderFrame)?.value?.toRenderStats())
    else -> null
  }

private fun RuntimeEvent.isAnimatedChange(): Boolean =
  (payload as? RuntimeEventPayload.CameraChange)?.value?.mode == CameraChangeMode.ANIMATED

private fun RuntimeEventRenderFrame.toRenderStats(): RenderStats =
  RenderStats(
    mode = mode.toCommon(),
    needsRepaint = needsRepaint,
    placementChanged = placementChanged,
    encodingTime = stats.encodingTimeMs.milliseconds,
    renderingTime = stats.renderingTimeMs.milliseconds,
    frameCount = stats.frameCount,
    drawCallCount = stats.drawCallCount,
    totalDrawCallCount = stats.totalDrawCallCount,
  )

/** The mode with the same name in MapLibre Native, or the native number as decimal text. */
internal fun RenderMode.toCommon(): RenderStats.Mode =
  when (this) {
    RenderMode.PARTIAL -> RenderStats.Mode.Partial
    RenderMode.FULL -> RenderStats.Mode.Full
    else -> RenderStats.Mode(rawValue.toString())
  }
