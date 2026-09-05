package org.maplibre.compose.map

import kotlin.time.Duration

/** One fact reported by the MapLibre engine behind a map. */
public sealed interface MapEvent {

  /**
   * The engine parsed the base style. The composition applies after this, so
   * [MapStyleState.loadState] reaches [StyleLoadState.Ready] later.
   */
  public data object StyleLoaded : MapEvent

  /**
   * The engine could not load the base style.
   *
   * [reason] is the engine's failure text, or a message from this library when the engine reports
   * none.
   */
  public data class StyleLoadFailed(val reason: String) : MapEvent

  /**
   * Native could not asynchronously serialize, prepare, or install GeoJSON data on [sourceId].
   *
   * The source retains its previously installed data, or remains empty if its initial data failed.
   * Superseded submissions and removed sources do not report failures. This event belongs to the
   * loaded base style that accepted the submission. URL loading errors are not reported here.
   */
  public data class SourceDataFailed(val sourceId: String, val cause: Throwable) : MapEvent

  /** The engine finished every pending load and render, and has nothing more to draw. */
  public data object Idle : MapEvent

  /**
   * The engine started one camera change. A drag reports one change per pointer move.
   *
   * [animated] is true for an animated transition and false for an immediate change. On the browser
   * it is null, because MapLibre GL JS reports no such distinction.
   */
  public data class CameraMoveStarted(val animated: Boolean?) : MapEvent

  /** The camera reached a new value inside a change that [CameraMoveStarted] began. */
  public data object CameraMoved : MapEvent

  /**
   * The engine finished one camera change.
   *
   * [animated] is true for an animated transition and false for an immediate change. On the browser
   * it is null, because MapLibre GL JS reports no such distinction.
   */
  public data class CameraMoveEnded(val animated: Boolean?) : MapEvent

  /**
   * The engine finished rendering one frame.
   *
   * [stats] holds the engine's measurements on native platforms. On the browser it is null, because
   * MapLibre GL JS reports no measurements with its render event.
   */
  public data class FrameRendered(val stats: RenderStats?) : MapEvent
}

/** The engine's measurements of one rendered frame. */
public data class RenderStats(
  /** Null for a render mode this version of the library does not name. */
  public val mode: Mode?,
  /** Whether the engine needs another frame to finish work this one started. */
  public val needsRepaint: Boolean,
  /** Whether symbol placement changed during the frame. */
  public val placementChanged: Boolean,
  /** Time the engine spent encoding this frame's draw commands. */
  public val encodingTime: Duration,
  /** Time the engine spent rendering this frame. */
  public val renderingTime: Duration,
  /** Frames the engine has rendered, including this one. */
  public val frameCount: Long,
  /** Draw calls the engine issued for this frame. */
  public val drawCallCount: Long,
  /** Draw calls the engine has issued, including this frame's. */
  public val totalDrawCallCount: Long,
) {
  /** Whether everything the frame needed had loaded when the engine drew it. */
  public enum class Mode {
    /** The engine drew before every tile and image the frame needed had loaded. */
    Partial,
    /** The engine drew with everything the frame needed. */
    Full,
  }
}
