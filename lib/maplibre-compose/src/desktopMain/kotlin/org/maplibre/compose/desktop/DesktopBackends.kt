package org.maplibre.compose.desktop

import androidx.compose.runtime.Immutable

/**
 * A graphics backend MapLibre Native can render a map with.
 *
 * MapLibre is the *producer*: it renders into a target that Compose later draws. Which backends are
 * available depends on the MapLibre Native FFI runtime the application packaged, not on this
 * library.
 */
public enum class MapRenderBackend {
  METAL,
  VULKAN,
  OPENGL,
}

/**
 * A graphics backend Compose can draw with.
 *
 * Compose is the *consumer*: it imports the target MapLibre rendered into and composites it with
 * the rest of the UI. Which backend is in use depends on the Compose host and operating system.
 */
public enum class ComposeRenderBackend {
  METAL,
  DIRECT3D12,
  OPENGL,
}

/**
 * One producer/consumer combination a [DesktopMapHostFactory] can bridge.
 *
 * A host does not support backends independently: bridging Vulkan to OpenGL is different work from
 * bridging Vulkan to Direct3D 12, so support is declared per pair.
 */
@Immutable
public data class DesktopBackendPair(
  /** The backend MapLibre Native renders with. */
  public val producer: MapRenderBackend,
  /** The backend Compose draws with. */
  public val consumer: ComposeRenderBackend,
) {
  override fun toString(): String = "$producer -> $consumer"
}

/**
 * What a live [DesktopMapHost] can do, beyond simply producing frames.
 *
 * The map session uses these to decide how defensively it has to behave; a host that cannot resize
 * without recreating its target, for example, forces a target generation change on every resize.
 */
@Immutable
public data class DesktopHostCapabilities(
  /** The backends this host bridges. */
  public val backends: DesktopBackendPair,
  /**
   * Whether the host synchronizes producer and consumer access explicitly, with fences or
   * semaphores, rather than relying on driver-level ordering.
   */
  public val supportsExplicitSynchronization: Boolean,
  /**
   * Whether the host can resize its render target in place. When false, a resize invalidates the
   * current target and the session must re-attach its render session.
   */
  public val supportsResizeWithoutRecreate: Boolean,
)
