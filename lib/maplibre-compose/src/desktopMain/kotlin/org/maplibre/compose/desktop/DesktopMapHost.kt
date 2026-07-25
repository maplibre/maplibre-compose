package org.maplibre.compose.desktop

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * One renderable frame produced by a [DesktopMapHost].
 *
 * A frame is valid only between [DesktopMapHost.acquireFrame] and [DesktopMapHost.releaseFrame].
 * Its [target] handles must not be retained past that window.
 */
public interface DesktopMapFrame {
  /** Monotonically increasing identifier, for logging and frame pacing. */
  public val frameId: Long

  /** The size this frame's target was allocated at. */
  public val extent: DesktopMapExtent

  /** The target MapLibre renders into. */
  public val target: DesktopRenderTarget

  /** When this frame is expected to be presented, in `System.nanoTime` units, if the host knows. */
  public val presentationTimeNanos: Long?
}

/**
 * The map session's view of its host, handed to [DesktopMapRenderer.onSurfaceAvailable].
 *
 * This is deliberately narrower than [DesktopMapHost]: the session asks for frames and for
 * serialized access to graphics state, but never drives the frame loop itself.
 */
public interface DesktopMapHostSession {
  /** The backends in use. */
  public val backends: DesktopBackendPair

  /** What this host can do. */
  public val capabilities: DesktopHostCapabilities

  /**
   * Asks the host to schedule another frame.
   *
   * Safe to call from any thread. Requests coalesce, so calling repeatedly before the next frame is
   * harmless.
   */
  public fun requestFrame()

  /**
   * Runs [action] with exclusive access to renderer graphics state, making the host's context
   * current if the backend needs it.
   *
   * Use this for graphics work outside a frame, such as tearing down a render session. Re-entrant
   * calls from the renderer thread run directly.
   */
  public fun <T> withRendererAccess(action: () -> T): T
}

/**
 * Bridges MapLibre Native's rendering to a Compose drawing surface.
 *
 * A host owns the GPU objects on both sides of the handoff: it allocates the target MapLibre
 * renders into, synchronizes the producer and consumer, and draws the finished target into Compose.
 * One host serves one map.
 *
 * Implement [DesktopMapHostFactory] to supply your own; see [LocalDesktopMapHostFactory].
 */
public interface DesktopMapHost : AutoCloseable {
  /** The backends this host bridges. */
  public val backends: DesktopBackendPair

  /** What this host can do. */
  public val capabilities: DesktopHostCapabilities

  /**
   * Resizes the host's render target.
   *
   * Called before the next [acquireFrame] whenever the surface extent changed. A host that cannot
   * resize in place reallocates and reports a new [DesktopRenderTarget.generation].
   */
  public fun resize(extent: DesktopMapExtent) {}

  /**
   * Acquires the next frame to render into.
   *
   * Throws if no target can be produced; the caller reports that as surface failure rather than
   * retrying.
   */
  public fun acquireFrame(
    frameId: Long,
    extent: DesktopMapExtent,
    presentationTimeNanos: Long?,
  ): DesktopMapFrame

  /** Runs [action] with the producer side able to render into [frame]'s target. */
  public fun <T> withProducerAccess(frame: DesktopMapFrame, action: () -> T): T = action()

  /**
   * Signals that the producer finished rendering into [frame] and the consumer may read it.
   *
   * Called only when the renderer reported [DesktopFrameResult.RENDERED]. This is where a host
   * inserts whatever fence or barrier its backends require.
   */
  public fun completeProducerAccess(frame: DesktopMapFrame) {}

  /** Releases [frame], whether or not it was rendered into. */
  public fun releaseFrame(frame: DesktopMapFrame) {}

  /** Runs [action] with exclusive access to renderer graphics state. */
  public fun <T> withRendererAccess(action: () -> T): T = action()

  /**
   * Draws [target] into the Compose scene, returning whether anything was drawn.
   *
   * [target] is the most recently completed target, which may be from an earlier frame if the
   * renderer skipped this one.
   */
  public fun draw(scope: DrawScope, target: DesktopRenderTarget): Boolean = false
}

/**
 * Creates the [DesktopMapHost] that backs a map.
 *
 * This is the extension point for applications that do not use the default Compose Desktop host: an
 * application supplying its own Compose windowing and GPU context implements this and provides it
 * through [LocalDesktopMapHostFactory]. Map behavior — camera, style, input, resources — does not
 * depend on which factory is in use.
 */
public interface DesktopMapHostFactory {
  /**
   * The producer/consumer combinations this factory can bridge on the current machine.
   *
   * Intersected with the backends the loaded MapLibre Native FFI runtime was built with to pick a
   * backend. May be empty, which produces a diagnostic rather than an exception.
   */
  public val supportedBackends: Set<DesktopBackendPair>

  /** A short description of this factory, used in diagnostics. */
  public val description: String

  /**
   * Creates a host rendering with [producer].
   *
   * Called only for a [producer] appearing in [supportedBackends]. Returning
   * [DesktopMapHostResult.Unsupported] or [DesktopMapHostResult.Failed] is preferred over throwing,
   * so the failure reaches the user as a diagnostic.
   */
  public fun create(producer: MapRenderBackend): DesktopMapHostResult
}

/** The outcome of [DesktopMapHostFactory.create]. */
public sealed interface DesktopMapHostResult {
  /** A usable host. */
  public data class Created(public val host: DesktopMapHost) : DesktopMapHostResult

  /** This factory cannot bridge the requested backend on this machine. */
  public data class Unsupported(public val diagnostic: String) : DesktopMapHostResult

  /** This factory should have been able to create a host, but failed. */
  public data class Failed(public val diagnostic: String, public val cause: Throwable? = null) :
    DesktopMapHostResult
}
