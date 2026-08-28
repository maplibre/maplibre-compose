package org.maplibre.compose.mlnffi

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.maplibre.compose.map.MapExtent

/** Pixel-aligned destination for a completed render target in a Compose draw scope. */
internal data class MlnFfiMapDestination(
  val left: Int,
  val top: Int,
  val width: Int,
  val height: Int,
) {
  val right: Int
    get() = left + width

  val bottom: Int
    get() = top + height
}

/** Aligns [sourceAnchor] with [destinationAnchor] without scaling [extent]. */
internal fun presentationDestination(
  extent: MapExtent,
  sourceAnchor: MlnFfiMapPresentationAnchor,
  destinationAnchor: MlnFfiMapPresentationAnchor,
): MlnFfiMapDestination {
  return MlnFfiMapDestination(
    left = destinationAnchor.x - sourceAnchor.x,
    top = destinationAnchor.y - sourceAnchor.y,
    width = extent.physicalWidth,
    height = extent.physicalHeight,
  )
}

/**
 * One renderable frame produced by a [MlnFfiMapHost].
 *
 * A frame is valid only between [MlnFfiMapHost.acquireFrame] and [MlnFfiMapHost.releaseFrame]. Its
 * [target] handles must not be retained past that window.
 */
internal data class MlnFfiMapFrame(
  /** Monotonically increasing identifier, for logging and frame pacing. */
  val frameId: Long,

  /** The size this frame's target was allocated at. */
  val extent: MapExtent,
  val target: MlnFfiRenderTarget,

  /**
   * When this frame is expected to be presented, if the host knows. Nanoseconds on a monotonic
   * clock whose origin is arbitrary, so only differences between frames mean anything.
   */
  val presentationTimeNanos: Long?,
)

/** The explicit outcome of asking a host for a frame. */
internal sealed interface MlnFfiMapFrameAcquisition {
  data class Acquired(val frame: MlnFfiMapFrame) : MlnFfiMapFrameAcquisition

  /** The consumer context does not exist yet; retry without changing recovery state. */
  data object NotReady : MlnFfiMapFrameAcquisition
}

/** The map session's view of its host, handed to [MlnFfiMapRenderer.onSurfaceAvailable]. */
internal interface MlnFfiMapHostSession {
  val backends: RenderBackendPair

  /** Asks the host to schedule another frame. Safe to call from any thread; requests coalesce. */
  fun requestFrame()

  /**
   * Runs [action] with exclusive access to renderer graphics state, making the host's context
   * current if the backend needs it. Re-entrant calls from the renderer thread run directly.
   */
  fun <T> withRendererAccess(action: () -> T): T

  /**
   * Queues [action] for the renderer thread without waiting. Returns false when the host can no
   * longer run it. Re-entrant calls from the renderer thread run directly.
   */
  fun enqueueRenderer(action: () -> Unit): Boolean {
    action()
    return true
  }
}

/**
 * Bridges MapLibre Native's rendering into whatever the platform composites with. A host owns the
 * GPU objects on both sides of the handoff, and serves one map.
 */
internal interface MlnFfiMapHost : AutoCloseable {
  val backends: RenderBackendPair

  /**
   * Resizes the host's render target, called before the next [acquireFrame] whenever the extent
   * changed. A host that cannot resize in place reallocates and reports a new
   * [MlnFfiRenderTarget.generation].
   */
  fun resize(extent: MapExtent) {}

  /**
   * Acquires the next frame to render into. Returns [MlnFfiMapFrameAcquisition.NotReady] when the
   * consumer graphics context does not exist yet; the caller skips that frame and asks for another
   * without entering failure recovery. Throws when a context exists but no target can be produced.
   * Called from the consumer's draw callback, so the host may use the consumer graphics context
   * that is current there.
   */
  fun acquireFrame(
    frameId: Long,
    extent: MapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrameAcquisition

  /** Runs [action] with the producer side able to render into [frame]'s target. */
  fun <T> withProducerAccess(frame: MlnFfiMapFrame, action: () -> T): T = action()

  /**
   * Signals that the producer finished rendering into [frame]. The implementation must ensure the
   * consumer can safely read the result. Called only when the renderer reported
   * [MlnFfiFrameResult.RENDERED].
   */
  fun completeProducerAccess(frame: MlnFfiMapFrame) {}

  /** Releases [frame], whether or not it was rendered into. */
  fun releaseFrame(frame: MlnFfiMapFrame) {}

  /** Runs [action] with exclusive access to renderer graphics state. */
  fun <T> withRendererAccess(action: () -> T): T = action()

  /**
   * Queues [action] for the renderer thread without waiting. Returns false when the host can no
   * longer run it. Re-entrant calls from the renderer thread run directly.
   */
  fun enqueueRenderer(action: () -> Unit): Boolean {
    action()
    return true
  }

  /**
   * Draws [target] at [destination], clipped in the Compose scene, and returns whether anything was
   * drawn.
   *
   * [target] is the most recently completed target, which may be from an earlier frame if the
   * renderer skipped this one.
   */
  fun draw(
    scope: DrawScope,
    target: MlnFfiRenderTarget,
    destination: MlnFfiMapDestination,
  ): Boolean
}

/** Creates the [MlnFfiMapHost] that backs a map. */
internal interface MlnFfiMapHostFactory {
  /** A short description of this factory, used in diagnostics. */
  val description: String

  /**
   * The producer/consumer combinations this factory can bridge on the current machine, in
   * preference order. The bridge the map uses is the first whose producer the packaged FFI runtime
   * provides; the runtime artifact the application packaged is what chooses between them.
   */
  val bridges: List<RenderBackendPair>

  /**
   * Creates a host for [backends], one of [bridges]. Prefer returning [MlnFfiMapHostResult.Failed]
   * over throwing, so the failure reaches the user as a diagnostic.
   */
  fun create(backends: RenderBackendPair): MlnFfiMapHostResult
}

/** The outcome of [MlnFfiMapHostFactory.create]. */
internal sealed interface MlnFfiMapHostResult {
  /** A usable host. */
  data class Created(val host: MlnFfiMapHost) : MlnFfiMapHostResult

  /** This factory should have been able to create a host, but failed. */
  data class Failed(val diagnostic: String, val cause: Throwable? = null) : MlnFfiMapHostResult
}
