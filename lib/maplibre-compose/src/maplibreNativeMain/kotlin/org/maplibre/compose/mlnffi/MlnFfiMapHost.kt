package org.maplibre.compose.mlnffi

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.maplibre.compose.map.MapExtent
import org.maplibre.nativeffi.AcquiredFrameHandle
import org.maplibre.nativeffi.FramePacerHandle

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

/**
 * One renderable frame produced by a [MlnFfiMapHost].
 *
 * Producer access is valid between [MlnFfiMapHost.acquireFrame] and [MlnFfiMapHost.releaseFrame].
 * After successful completion, the host keeps [target] presentable until a newer completed target
 * is drawn or the host closes, including across resize.
 */
internal data class MlnFfiMapFrame(val target: MlnFfiRenderTarget) {
  /**
   * Native ownership, transferred to the host after rendering. The argument reports whether the
   * consumer finished its GPU reads; false abandons the session before releasing the frame.
   */
  var releaseAfterReads: ((consumerCompleted: Boolean) -> Unit)? = null

  /**
   * The frame the session acquired, for a host that reads a session-allocated texture from it, as a
   * ring target needs. It stays valid until [releaseAfterReads] runs. The host must not release it
   * itself.
   */
  var acquiredFrame: AcquiredFrameHandle? = null
}

/** The explicit outcome of asking a host for a frame. */
internal sealed interface MlnFfiMapFrameAcquisition {
  data class Acquired(val frame: MlnFfiMapFrame) : MlnFfiMapFrameAcquisition

  /** The consumer context does not exist yet; retry without changing recovery state. */
  data object NotReady : MlnFfiMapFrameAcquisition
}

/** How a render session's frames are demanded. */
internal sealed interface MlnFfiFrameDemands {
  /**
   * Each frame pass parks one demand that waits for the next map update, and the host schedules the
   * next pass when a frame result arrives.
   */
  data object Parked : MlnFfiFrameDemands

  /**
   * [pacer] demands frames at display refreshes. Frame passes only take frame results. A pass that
   * finds another pacer than the session's gives the session the new one.
   */
  class Paced(val pacer: FramePacerHandle) : MlnFfiFrameDemands

  /**
   * The session's redraw wake asks the host for a frame pass, which demands one frame. The host
   * schedules the pass at its next display frame, and the session has one demand at a time, so the
   * host's own clock paces rendering.
   */
  data object OnRedraw : MlnFfiFrameDemands
}

/** The map session's view of its host, handed to [MlnFfiMapRenderer.onSurfaceAvailable]. */
internal interface MlnFfiMapHostSession {
  /** Logical host closure rejects a delayed surface offer before physical teardown finishes. */
  val isClosed: Boolean

  val backends: RenderBackendPair

  /**
   * Who demands the frames of a render session attached to this host. Read on the renderer thread
   * at every frame pass.
   */
  val frameDemands: MlnFfiFrameDemands
    get() = MlnFfiFrameDemands.Parked

  /** Asks the host to schedule another frame. Safe to call from any thread; requests coalesce. */
  fun requestFrame()

  /** Finishes consumer reads and releases held native frames before session teardown. */
  fun completeConsumerAccess() {}

  /**
   * Runs [action] with exclusive access to renderer graphics state, making the host's context
   * current if the backend needs it. Re-entrant calls from the renderer thread run directly.
   */
  fun <T> withRendererAccess(action: () -> T): T

  /**
   * Queues [action] for the renderer thread without waiting. Returns false when the host can no
   * longer run it. After rejecting work, the host must still deliver `onSurfaceLost` before
   * destroying its targets or stopping the renderer thread. Re-entrant calls from that thread run
   * directly.
   */
  fun enqueueRenderer(action: () -> Unit): Boolean
}

/**
 * Bridges MapLibre Native's rendering into whatever the platform composites with. A host owns the
 * GPU objects on both sides of the handoff, and serves one map.
 */
internal interface MlnFfiMapHost : AutoCloseable {
  val backends: RenderBackendPair

  /** See [MlnFfiMapHostSession.frameDemands]. */
  val frameDemands: MlnFfiFrameDemands
    get() = MlnFfiFrameDemands.Parked

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
   * Called before Compose overlay placement. The host must obtain exclusive access to the consumer
   * graphics context when needed; it cannot assume a draw callback made it current.
   */
  fun acquireFrame(extent: MapExtent): MlnFfiMapFrameAcquisition

  /** Runs [action] with the producer side able to render into [frame]'s target. */
  fun <T> withProducerAccess(frame: MlnFfiMapFrame, action: () -> T): T = action()

  /**
   * Signals that the producer finished rendering into [frame]. The implementation must ensure the
   * consumer can safely read the result. Called only when the renderer reported
   * [MlnFfiFrameResult.Rendered].
   */
  fun completeProducerAccess(frame: MlnFfiMapFrame) {}

  /** Finishes consumer reads and releases held native frames before session teardown. */
  fun completeConsumerAccess() {}

  /** Releases ownership that was not transferred to the consumer, including on failure. */
  fun releaseFrame(frame: MlnFfiMapFrame) {
    frame.acquiredFrame = null
    frame.releaseAfterReads?.invoke(true)
    frame.releaseAfterReads = null
  }

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
