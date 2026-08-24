package org.maplibre.compose.mlnffi

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.maplibre.compose.map.MapExtent

/**
 * One renderable frame produced by a [MlnFfiMapHost].
 *
 * The host owns the frame from [MlnFfiMapHost.acquireFrame] through [MlnFfiMapHost.produceFrame]. A
 * completed [target] remains available to the consumer until the host replaces its generation or
 * closes.
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

/** The result that a host has made available after producing a frame. */
internal sealed interface MlnFfiMapFrameProduction {
  /** A producer invocation finished and its target is safe for the consumer to inspect. */
  data class Completed(val result: MlnFfiFrameResult, val target: MlnFfiRenderTarget) :
    MlnFfiMapFrameProduction

  /** The producer is still working. It calls the supplied frame request when work finishes. */
  data object Pending : MlnFfiMapFrameProduction
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

  /**
   * Produces one frame and owns [frame] until the producer has finished with it.
   *
   * Synchronous hosts return [MlnFfiMapFrameProduction.Completed]. An asynchronous host returns
   * [MlnFfiMapFrameProduction.Pending] and calls [requestFrame] when the result is ready to
   * collect. [producerRequested] distinguishes renderer work from a draw scheduled only to collect
   * asynchronous completion. Every implementation releases [frame] exactly once.
   */
  fun produceFrame(
    frame: MlnFfiMapFrame,
    requestFrame: () -> Unit,
    producerRequested: Boolean = true,
    action: () -> MlnFfiFrameResult,
  ): MlnFfiMapFrameProduction =
    try {
      val result = withProducerAccess(frame, action)
      if (result == MlnFfiFrameResult.RENDERED) completeProducerAccess(frame)
      MlnFfiMapFrameProduction.Completed(result, frame.target)
    } finally {
      releaseFrame(frame)
    }

  /** Runs [action] with the producer side able to render into [frame]'s target. */
  fun <T> withProducerAccess(frame: MlnFfiMapFrame, action: () -> T): T = action()

  /**
   * Signals that the producer finished rendering into [frame]. The implementation must ensure the
   * consumer can safely read the result. Called only when the renderer reported
   * [MlnFfiFrameResult.RENDERED].
   */
  fun completeProducerAccess(frame: MlnFfiMapFrame) {}

  /**
   * Releases producer access to [frame], whether or not it was rendered into. [produceFrame] calls
   * this exactly once for its default synchronous path.
   */
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
   * Draws [target] into the Compose scene, returning whether anything was drawn.
   *
   * [target] is the most recently completed target, which may be from an earlier frame if the
   * renderer skipped this one.
   */
  fun draw(scope: DrawScope, target: MlnFfiRenderTarget): Boolean
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
