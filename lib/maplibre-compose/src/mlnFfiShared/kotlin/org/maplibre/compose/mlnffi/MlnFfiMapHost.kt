package org.maplibre.compose.mlnffi

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.drawscope.DrawScope

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
  val extent: MlnFfiMapExtent,
  val target: MlnFfiRenderTarget,

  /** When this frame is expected to be presented, in `System.nanoTime` units, if the host knows. */
  val presentationTimeNanos: Long?,
)

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
}

/**
 * Bridges MapLibre Native's rendering into whatever the platform composites with.
 *
 * A host owns the GPU objects on both sides of the handoff: it allocates the target MapLibre
 * renders into, synchronizes the producer and consumer, and presents the finished target. One host
 * serves one map.
 */
internal interface MlnFfiMapHost : AutoCloseable {
  val backends: RenderBackendPair

  /**
   * Resizes the host's render target, called before the next [acquireFrame] whenever the extent
   * changed. A host that cannot resize in place reallocates and reports a new
   * [MlnFfiRenderTarget.generation].
   */
  fun resize(extent: MlnFfiMapExtent) {}

  /**
   * Acquires the next frame to render into. Throws if no target can be produced; the caller reports
   * that as surface failure rather than retrying.
   */
  fun acquireFrame(
    frameId: Long,
    extent: MlnFfiMapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrame

  /** Runs [action] with the producer side able to render into [frame]'s target. */
  fun <T> withProducerAccess(frame: MlnFfiMapFrame, action: () -> T): T = action()

  /**
   * Signals that the producer finished rendering into [frame] and the consumer may read it; where a
   * host inserts whatever fence its backends require. Called only when the renderer reported
   * [MlnFfiFrameResult.RENDERED].
   */
  fun completeProducerAccess(frame: MlnFfiMapFrame) {}

  /** Releases [frame], whether or not it was rendered into. */
  fun releaseFrame(frame: MlnFfiMapFrame) {}

  /** Runs [action] with exclusive access to renderer graphics state. */
  fun <T> withRendererAccess(action: () -> T): T = action()

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
   * The producer/consumer combinations this factory can bridge on the current machine. May be
   * empty, which produces a diagnostic rather than an exception.
   */
  val supportedBackends: Set<RenderBackendPair>

  /**
   * Creates a host rendering with [producer], which always appears in [supportedBackends]. Prefer
   * returning [MlnFfiMapHostResult.Failed] over throwing, so the failure reaches the user as a
   * diagnostic.
   */
  fun create(producer: MapRenderBackend): MlnFfiMapHostResult
}

/** The outcome of [MlnFfiMapHostFactory.create]. */
internal sealed interface MlnFfiMapHostResult {
  /** A usable host. */
  data class Created(val host: MlnFfiMapHost) : MlnFfiMapHostResult

  /** This factory should have been able to create a host, but failed. */
  data class Failed(val diagnostic: String, val cause: Throwable? = null) : MlnFfiMapHostResult
}

/**
 * Replaces the host a map would otherwise build for the platform it is running on.
 *
 * Null, and meant to stay null outside this library's own tests: it exists so the render path can
 * be driven headlessly, with no window and no GPU context to hand out.
 */
internal val LocalMlnFfiMapHostFactory: ProvidableCompositionLocal<MlnFfiMapHostFactory?> =
  staticCompositionLocalOf {
    null
  }
