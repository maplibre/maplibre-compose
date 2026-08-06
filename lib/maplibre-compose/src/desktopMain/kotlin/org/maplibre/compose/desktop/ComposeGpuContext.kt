package org.maplibre.compose.desktop

import androidx.compose.runtime.Immutable
import org.jetbrains.skia.DirectContext
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.NativeHandle

/**
 * The GPU context a Compose host draws with.
 *
 * Given the device Compose renders with and the Skia context wrapping it, the library allocates the
 * target MapLibre renders into, synchronizes the handoff, and draws the result into the Compose
 * scene.
 *
 * The handles are borrowed. MapLibre Compose never retains, releases, or frees them; they must stay
 * valid until the host reports a different context.
 */
@Immutable
public sealed interface ComposeGpuContext {
  /** The Skia context Compose draws this scene with. */
  public val skiaContext: DirectContext

  /** Which Compose backend this context is for. */
  public val backend: ComposeRenderBackend
}

/** A Metal context, used by Compose on macOS. */
@Immutable
public class MetalComposeGpuContext(
  override val skiaContext: DirectContext,
  /** `id<MTLDevice>` Compose renders with. MapLibre's texture is allocated on the same device. */
  public val device: NativeHandle,
) : ComposeGpuContext {
  override val backend: ComposeRenderBackend
    get() = ComposeRenderBackend.METAL
}

/**
 * An OpenGL context, used by Compose on Linux.
 *
 * OpenGL work is bound to whichever context is current on the calling thread, so this carries
 * [withContextCurrent] rather than a context handle alone.
 */
@Immutable
public class OpenGlComposeGpuContext(
  override val skiaContext: DirectContext,
  /**
   * Runs [Runnable] with this context current on the calling thread.
   *
   * Scoped rather than a bare `makeCurrent`, because a host may have to hold a lock on its drawing
   * surface for as long as the context is current. Must be safe to nest.
   */
  public val withContextCurrent: (Runnable) -> Unit,
) : ComposeGpuContext {
  override val backend: ComposeRenderBackend
    get() = ComposeRenderBackend.OPENGL
}

/** A Direct3D 12 context, used by Compose on Windows. */
@Immutable
public class Direct3D12ComposeGpuContext(
  override val skiaContext: DirectContext,
  /** `ID3D12Device` Compose renders with. MapLibre's shared texture is created on it. */
  public val device: NativeHandle,
) : ComposeGpuContext {
  override val backend: ComposeRenderBackend
    get() = ComposeRenderBackend.DIRECT3D12
}

/**
 * Supplies the [ComposeGpuContext] a map renders against.
 *
 * The extension point for applications running their own Compose windowing: report the context, and
 * say which thread owns it. Install one with [ProvideMapHost].
 */
public interface ComposeGpuHost {
  /** A short description of this host, used in diagnostics. */
  public val description: String

  /**
   * The backend Compose draws this scene with, which decides how MapLibre's output reaches it.
   *
   * Separate from [gpuContext] because it is settled the moment this host exists, while the context
   * object may not be: a host that answers here lets a map report an unbridgeable combination up
   * front instead of blanking. It must agree with the type [gpuContext] returns.
   */
  public val backend: ComposeRenderBackend

  /**
   * The context Compose is currently drawing with, or null when it does not exist yet — Skia
   * contexts are commonly created while producing the first frame, which the map reports as a
   * skipped frame.
   *
   * Always called on the thread [runOnGpuThread] runs on, so this may read state confined to it and
   * need not hop there itself.
   */
  public fun gpuContext(): ComposeGpuContext?

  /**
   * Runs [action] on the thread that owns this host's Skia context, and waits for it.
   *
   * Skia objects wrapping MapLibre's target are created and freed there, so this must be the same
   * thread that draws the Compose scene. It must run [action] directly when the caller is already
   * on that thread, and must propagate whatever [action] throws.
   */
  public fun runOnGpuThread(action: Runnable)
}

/** Runs [action] on [ComposeGpuHost.runOnGpuThread] and returns its result. */
internal fun <T> ComposeGpuHost.onGpuThread(action: () -> T): T {
  var result: Result<T>? = null
  runOnGpuThread { result = runCatching(action) }
  return checkNotNull(result) { "$description did not run the action it was given" }.getOrThrow()
}
