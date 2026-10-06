package org.maplibre.compose.desktop

import androidx.compose.runtime.Immutable
import org.jetbrains.skia.DirectContext
import org.maplibre.compose.mlnffi.NativeHandle

internal interface ComposeGpuContext {
  val skiaContext: DirectContext
}

/**
 * A Metal context, used by Compose on macOS.
 *
 * [skiaContext] and [device] are borrowed. The host must keep them valid until its context callback
 * reports a replacement, and serialize replacement and disposal with its GPU access callback.
 * MapLibre Compose does not close the Skia context or dispose the host's device.
 */
@Immutable
public class MetalComposeGpuContext(
  /** The Skia context Compose draws this scene with. */
  override val skiaContext: DirectContext,
  /** `id<MTLDevice>` Compose renders with. MapLibre's texture is allocated on the same device. */
  public val device: NativeHandle,
) : ComposeGpuContext

/**
 * An OpenGL context, used by Linux OpenGL and Windows ANGLE Compose hosts.
 *
 * [skiaContext] is borrowed. The host must keep it valid until its context callback reports a
 * replacement, and serialize replacement and disposal with its GPU access callback. MapLibre
 * Compose does not close the Skia context.
 *
 * OpenGL work is bound to whichever context is current on the calling thread, so this carries
 * [withContextCurrent] rather than a context handle alone.
 */
@Immutable
public class OpenGlComposeGpuContext(
  /** The Skia context Compose draws this scene with. */
  override val skiaContext: DirectContext,
  /**
   * Runs [Runnable] with this context current on the calling thread.
   *
   * Scoped rather than a bare `makeCurrent`, because a host may have to hold a lock on its drawing
   * surface for as long as the context is current. Must run synchronously, be safe to nest, and
   * release that access when the action returns or throws. Called under the host's GPU access.
   */
  public val withContextCurrent: (Runnable) -> Unit,
) : ComposeGpuContext

/**
 * A Direct3D 12 context, used by Compose on Windows.
 *
 * [skiaContext] and [device] are borrowed. The host must keep them valid until its context callback
 * reports a replacement, and serialize replacement and disposal with its GPU access callback.
 * MapLibre Compose does not close the Skia context or dispose the host's device.
 */
@Immutable
public class Direct3D12ComposeGpuContext(
  /** The Skia context Compose draws this scene with. */
  override val skiaContext: DirectContext,
  /** `ID3D12Device` Compose renders with. MapLibre's shared texture is created on it. */
  public val device: NativeHandle,
) : ComposeGpuContext
