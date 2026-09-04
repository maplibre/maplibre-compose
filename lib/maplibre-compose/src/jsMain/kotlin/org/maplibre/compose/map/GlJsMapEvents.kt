package org.maplibre.compose.map

import org.maplibre.compose.gljs.GlJsMapEvent

internal typealias GlJsEventTranslation = (GlJsMapEvent) -> MapEvent

/** MapLibre GL JS events that report the engine's own progress. */
internal val ENGINE_GL_JS_EVENTS: Map<String, GlJsEventTranslation> =
  mapOf("idle" to { MapEvent.Idle })

/** MapLibre GL JS events that belong to the render lease that produced them. */
internal val PRESENTATION_GL_JS_EVENTS: Map<String, GlJsEventTranslation> =
  mapOf(
    "movestart" to { MapEvent.CameraMoveStarted(animated = null) },
    "move" to { MapEvent.CameraMoved },
    "moveend" to { MapEvent.CameraMoveEnded(animated = null) },
    "render" to { MapEvent.FrameRendered(stats = null) },
  )
