package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline
import org.maplibre.compose.map.MapExtent
import org.maplibre.nativeffi.OpenGlClientApi
import org.maplibre.nativeffi.OpenGlContextOwnership
import org.maplibre.nativeffi.VulkanDeviceHandle

/**
 * A borrowed pointer address or 64-bit Vulkan handle. The host owns the resource; MapLibre Compose
 * never frees, retains, or dereferences it.
 */
@Immutable
@JvmInline
public value class NativeHandle(public val address: Long) {
  public val isNull: Boolean
    get() = address == 0L

  override fun toString(): String = "NativeHandle(0x${address.toString(16)})"
}

/** Whether a texture's first row is its top or its bottom. */
internal enum class TextureOrigin {
  TopLeft,
  BottomLeft,
}

/**
 * A render target the host has allocated for MapLibre Native to render into. These are *borrowed*:
 * the host allocates, recycles, and frees them.
 */
internal sealed interface MlnFfiRenderTarget {
  /** The backend MapLibre must render with to use this target. */
  val backend: MapRenderBackend

  val extent: MapExtent

  /**
   * Identifies the underlying target allocation.
   *
   * Increment this when handles change allocation, including after resize, surface loss, or pool
   * rotation. Keep the retired allocation readable until asked to draw a different one.
   */
  val generation: Long
}

/**
 * A platform window that MapLibre presents to with a graphics context that it creates, rendering on
 * its own worker. A platform source set defines each kind and attaches it through
 * `attachPlatformTarget`.
 */
internal interface PlatformWindowTarget : MlnFfiRenderTarget {
  /** The platform's window object. A different one needs the session to present to it instead. */
  val window: Any
}

/** Vulkan instance and device handles MapLibre needs to render into a Vulkan target. */
@Immutable
internal data class VulkanContextHandles(
  /** `VkInstance`. */
  val instance: NativeHandle,
  /** `VkPhysicalDevice`. */
  val physicalDevice: NativeHandle,
  /** `VkDevice`. */
  val device: NativeHandle,
  /** `VkQueue` for graphics work. */
  val graphicsQueue: NativeHandle,
  /** Queue family index of [graphicsQueue]. */
  val graphicsQueueFamilyIndex: Int,
  /** `PFN_vkGetInstanceProcAddr`. */
  val getInstanceProcAddr: NativeHandle,
  /** `PFN_vkGetDeviceProcAddr`. */
  val getDeviceProcAddr: NativeHandle,
)

/** A `VkImage` MapLibre renders into. */
@Immutable
internal data class VulkanImageTarget(
  /** The Vulkan context owning [image]. */
  val context: VulkanContextHandles,
  /** `VkImage`. */
  val image: NativeHandle,
  /** `VkImageView` over [image]. */
  val imageView: NativeHandle,
  /** `VkFormat` of [image]. */
  val format: Int,
  /** `VkImageLayout` MapLibre must leave [image] in for the host to consume it. */
  val finalLayout: Int,
  override val extent: MapExtent,
  override val generation: Long,
) : MlnFfiRenderTarget {
  override val backend: MapRenderBackend
    get() = MapRenderBackend.Vulkan
}

/** An `id<MTLTexture>` MapLibre renders into. */
@Immutable
internal data class MetalTextureTarget(
  /** `id<MTLTexture>`. */
  val texture: NativeHandle,
  /** `MTLPixelFormat` of [texture]. */
  val pixelFormat: Long,
  /** Row order of [texture]. */
  val origin: TextureOrigin,
  override val extent: MapExtent,
  override val generation: Long,
) : MlnFfiRenderTarget {
  override val backend: MapRenderBackend
    get() = MapRenderBackend.Metal
}

/**
 * A `CAMetalLayer` MapLibre renders into and presents directly.
 *
 * The session writes the layer's `drawableSize` from [extent]'s physical size when it attaches,
 * resizes, or retargets.
 */
@Immutable
internal data class MetalSurfaceTarget(
  /** `id<MTLDevice>` for the session, or [NativeHandle.isNull] for the system default device. */
  val device: NativeHandle,
  /** The `CAMetalLayer` MapLibre presents through. */
  val layer: NativeHandle,
  override val extent: MapExtent,
  override val generation: Long,
) : MlnFfiRenderTarget {
  override val backend: MapRenderBackend
    get() = MapRenderBackend.Metal
}

/**
 * A ring of `IOSurface`s that the session allocates and renders into on [device]. The host reads
 * each frame's surface from the frame the session acquired, and resizes the ring through the
 * session, so a new [generation] means a new device.
 */
@Immutable
internal data class IoSurfaceRingTarget(
  /** `id<MTLDevice>` the session renders with, the one the host reads the surfaces with. */
  val device: NativeHandle,
  /** How many surfaces the ring has. */
  val bufferCount: Int,
  override val extent: MapExtent,
  override val generation: Long,
) : MlnFfiRenderTarget {
  override val backend: MapRenderBackend
    get() = MapRenderBackend.Metal
}

/**
 * A ring of DXGI shared textures that the session allocates and renders into with [device]. The
 * host opens each frame's shared handle from the frame the session acquired, and resizes the ring
 * through the session, so a new [generation] means a new device.
 */
@Immutable
internal data class DxgiSharedTextureRingTarget(
  /** The Vulkan device the session renders with, on the GPU the host reads the textures with. */
  val device: VulkanDeviceHandle,
  /** How many textures the ring has. */
  val textureCount: Int,
  override val extent: MapExtent,
  override val generation: Long,
) : MlnFfiRenderTarget {
  override val backend: MapRenderBackend
    get() = MapRenderBackend.Vulkan
}

/** Platform context handles MapLibre needs to render into an OpenGL target. */
internal sealed interface OpenGlContextHandles

/** EGL context handles, used by EGL hosts. */
@Immutable
internal data class EglContextHandles(
  /** `EGLDisplay`. */
  val display: NativeHandle,
  /** `EGLConfig`. */
  val config: NativeHandle,
  /**
   * `EGLContext` whose share group the session joins. Unused when [ownership] is
   * [OpenGlContextOwnership.DEDICATED], where the session creates a context of its own.
   */
  val shareContext: NativeHandle,
  /** `eglGetProcAddress`. */
  val getProcAddress: NativeHandle,
  /**
   * Whether the session shares this thread with host graphics work. A dedicated session owns the
   * thread's context.
   */
  val ownership: OpenGlContextOwnership = OpenGlContextOwnership.SHARED,
  /**
   * Client API a dedicated session creates its context for. Ignored under shared ownership, where
   * the session reads it from [shareContext].
   */
  val clientApi: OpenGlClientApi = OpenGlClientApi.UNSPECIFIED,
) : OpenGlContextHandles

/** WGL context handles, used by WGL hosts. */
@Immutable
internal data class WglContextHandles(
  /** `HDC`. */
  val deviceContext: NativeHandle,
  /** `HGLRC` MapLibre's context should share objects with. */
  val shareContext: NativeHandle,
  /** `wglGetProcAddress`. */
  val getProcAddress: NativeHandle,
  val ownership: OpenGlContextOwnership = OpenGlContextOwnership.SHARED,
) : OpenGlContextHandles

/**
 * An OpenGL texture MapLibre renders into. OpenGL work is bound to whichever context is current on
 * the calling thread, so the target carries [makeContextCurrent] as well as its context handles.
 */
@Immutable
internal data class OpenGlTextureTarget(
  /** Platform context handles for the context [textureName] belongs to. */
  val context: OpenGlContextHandles,
  /** GL texture name. */
  val textureName: Int,
  /** GL texture target, such as `GL_TEXTURE_2D`. */
  val textureTarget: Int,
  /** GL sized internal format of the texture. */
  val format: Int,
  /** Row order of the texture. */
  val origin: TextureOrigin,
  /**
   * Makes the context owning this texture current on the calling thread.
   *
   * The session calls this before any OpenGL work touching the target. It must be safe to call when
   * the context is already current.
   */
  val makeContextCurrent: () -> Unit,
  override val extent: MapExtent,
  override val generation: Long,
) : MlnFfiRenderTarget {
  override val backend: MapRenderBackend
    get() = MapRenderBackend.OpenGl
}

/** A platform-native OpenGL surface MapLibre renders into and presents directly. */
@Immutable
internal data class OpenGlSurfaceTarget(
  /** Platform context handles for [surface]. */
  val context: OpenGlContextHandles,
  /** Provider-native surface handle, or zero when the context identifies a WebGL canvas. */
  val surface: NativeHandle,
  override val extent: MapExtent,
  override val generation: Long,
) : MlnFfiRenderTarget {
  override val backend: MapRenderBackend
    get() = MapRenderBackend.OpenGl
}
