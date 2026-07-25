package org.maplibre.compose.desktop.skiko

import org.maplibre.compose.desktop.ComposeRenderBackend
import org.maplibre.compose.desktop.DesktopBackendPair
import org.maplibre.compose.desktop.DesktopMapHostFactory
import org.maplibre.compose.desktop.DesktopMapHostResult
import org.maplibre.compose.desktop.MapRenderBackend

/** Operating systems the default host distinguishes between. */
internal enum class HostOperatingSystem {
  LINUX,
  MACOS,
  WINDOWS,
  UNSUPPORTED;

  /** The backend Compose Desktop draws with here. */
  val composeBackend: ComposeRenderBackend?
    get() =
      when (this) {
        LINUX -> ComposeRenderBackend.OPENGL
        MACOS -> ComposeRenderBackend.METAL
        WINDOWS -> ComposeRenderBackend.DIRECT3D12
        UNSUPPORTED -> null
      }

  companion object {
    fun current(): HostOperatingSystem {
      val os = System.getProperty("os.name")?.lowercase().orEmpty()
      return when {
        os.contains("linux") -> LINUX
        os.contains("mac") -> MACOS
        os.contains("windows") -> WINDOWS
        else -> UNSUPPORTED
      }
    }
  }
}

/**
 * The default [DesktopMapHostFactory], bridging MapLibre into Compose Desktop's Skiko renderer.
 *
 * Reads Compose's GPU context reflectively, since Compose Desktop exposes no supported hook for it;
 * all of that is confined to [SkikoReflection]. Applications with their own Compose host should
 * supply their own factory through `LocalDesktopMapHostFactory` rather than relying on this.
 */
public object SkikoDesktopMapHostFactory : DesktopMapHostFactory {

  private val operatingSystem = HostOperatingSystem.current()

  override val description: String
    get() = "the default Compose Desktop (Skiko) host on ${operatingSystem.name.lowercase()}"

  override val supportedBackends: Set<DesktopBackendPair>
    get() =
      when (operatingSystem) {
        HostOperatingSystem.LINUX ->
          setOf(DesktopBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL))
        // TODO(maplibre-compose): port the Windows Vulkan-to-Direct3D 12 and macOS Metal-to-Metal
        // bridges from the maplibre-native-ffi Compose example. Declaring them unsupported until
        // then produces an actionable diagnostic instead of a crash mid-frame.
        HostOperatingSystem.MACOS,
        HostOperatingSystem.WINDOWS,
        HostOperatingSystem.UNSUPPORTED -> emptySet()
      }

  override fun create(producer: MapRenderBackend): DesktopMapHostResult {
    val pair =
      supportedBackends.firstOrNull { it.producer == producer }
        ?: return DesktopMapHostResult.Unsupported(
          "$description cannot bridge $producer to Compose's " +
            "${operatingSystem.composeBackend ?: "unknown"} renderer."
        )

    return try {
      when (pair.producer) {
        MapRenderBackend.VULKAN -> DesktopMapHostResult.Created(LinuxVulkanOpenGlHost())
        else -> DesktopMapHostResult.Unsupported("$description has no bridge for ${pair.producer}.")
      }
    } catch (error: Throwable) {
      if (error is VirtualMachineError) throw error
      DesktopMapHostResult.Failed("$description failed to create a $producer bridge", error)
    }
  }
}
