package org.maplibre.compose.desktop

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * One renderable frame produced by a [DesktopMapHost].
 *
 * A frame is valid only between [DesktopMapHost.acquireFrame] and [DesktopMapHost.releaseFrame].
 * Its [target] handles must not be retained past that window.
 */
internal data class DesktopMapFrame(
  /** Monotonically increasing identifier, for logging and frame pacing. */
  val frameId: Long,

  /** The size this frame's target was allocated at. */
  val extent: DesktopMapExtent,
  val target: DesktopRenderTarget,

  /** When this frame is expected to be presented, in `System.nanoTime` units, if the host knows. */
  val presentationTimeNanos: Long?,
)

/** The map session's view of its host, handed to [DesktopMapRenderer.onSurfaceAvailable]. */
internal interface DesktopMapHostSession {
  val backends: DesktopBackendPair

  /** Asks the host to schedule another frame. Safe to call from any thread; requests coalesce. */
  fun requestFrame()

  /**
   * Runs [action] with exclusive access to renderer graphics state, making the host's context
   * current if the backend needs it. Re-entrant calls from the renderer thread run directly.
   */
  fun <T> withRendererAccess(action: () -> T): T
}

/**
 * Bridges MapLibre Native's rendering to a Compose drawing surface.
 *
 * A host owns the GPU objects on both sides of the handoff: it allocates the target MapLibre
 * renders into, synchronizes the producer and consumer, and draws the finished target into Compose.
 * One host serves one map.
 *
 * Internal, and staying that way: an application supplies a [DesktopComposeGpuHost] and the library
 * builds the matching bridge itself. This interface survives only because the headless test host is
 * not a Compose GPU context at all — it renders into a plain Vulkan image and draws nothing.
 */
internal interface DesktopMapHost : AutoCloseable {
  val backends: DesktopBackendPair

  /**
   * Resizes the host's render target, called before the next [acquireFrame] whenever the extent
   * changed. A host that cannot resize in place reallocates and reports a new
   * [DesktopRenderTarget.generation].
   */
  fun resize(extent: DesktopMapExtent) {}

  /**
   * Acquires the next frame to render into. Throws if no target can be produced; the caller reports
   * that as surface failure rather than retrying.
   */
  fun acquireFrame(
    frameId: Long,
    extent: DesktopMapExtent,
    presentationTimeNanos: Long?,
  ): DesktopMapFrame

  /** Runs [action] with the producer side able to render into [frame]'s target. */
  fun <T> withProducerAccess(frame: DesktopMapFrame, action: () -> T): T = action()

  /**
   * Signals that the producer finished rendering into [frame] and the consumer may read it; where a
   * host inserts whatever fence its backends require. Called only when the renderer reported
   * [DesktopFrameResult.RENDERED].
   */
  fun completeProducerAccess(frame: DesktopMapFrame) {}

  /** Releases [frame], whether or not it was rendered into. */
  fun releaseFrame(frame: DesktopMapFrame) {}

  /** Runs [action] with exclusive access to renderer graphics state. */
  fun <T> withRendererAccess(action: () -> T): T = action()

  /**
   * Draws [target] into the Compose scene, returning whether anything was drawn.
   *
   * [target] is the most recently completed target, which may be from an earlier frame if the
   * renderer skipped this one.
   */
  fun draw(scope: DrawScope, target: DesktopRenderTarget): Boolean
}

/** Creates the [DesktopMapHost] that backs a map. */
internal interface DesktopMapHostFactory {
  /** A short description of this factory, used in diagnostics. */
  val description: String

  /**
   * The producer/consumer combinations this factory can bridge on the current machine. May be
   * empty, which produces a diagnostic rather than an exception.
   */
  val supportedBackends: Set<DesktopBackendPair>

  /**
   * Creates a host rendering with [producer], which always appears in [supportedBackends]. Prefer
   * returning [DesktopMapHostResult.Failed] over throwing, so the failure reaches the user as a
   * diagnostic.
   */
  fun create(producer: MapRenderBackend): DesktopMapHostResult
}

/** The outcome of [DesktopMapHostFactory.create]. */
internal sealed interface DesktopMapHostResult {
  /** A usable host. */
  data class Created(val host: DesktopMapHost) : DesktopMapHostResult

  /** This factory should have been able to create a host, but failed. */
  data class Failed(val diagnostic: String, val cause: Throwable? = null) : DesktopMapHostResult
}

/**
 * Replaces the bridge a map would otherwise build from [LocalDesktopComposeGpuHost].
 *
 * Null, and meant to stay null outside this library's own tests. It exists so the desktop path can
 * be driven headlessly, against a host that renders into a plain Vulkan image with no window, no
 * Compose scene, and no Skia context to hand out.
 */
internal val LocalDesktopMapHostFactory: ProvidableCompositionLocal<DesktopMapHostFactory?> =
  staticCompositionLocalOf {
    null
  }
