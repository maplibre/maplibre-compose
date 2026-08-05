package org.maplibre.compose.desktop

import androidx.compose.runtime.Immutable

/**
 * A graphics backend MapLibre Native can render a map with.
 *
 * MapLibre is the *producer*: it renders into a target that Compose later draws. Which backends are
 * available depends on the MapLibre Native FFI runtime the application packaged.
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
 * Support is declared per pair, since bridging Vulkan to OpenGL is different work from bridging
 * Vulkan to Direct3D 12.
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
