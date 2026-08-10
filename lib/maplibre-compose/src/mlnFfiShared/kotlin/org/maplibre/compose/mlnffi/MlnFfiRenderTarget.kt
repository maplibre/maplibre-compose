package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Immutable
import org.maplibre.compose.map.MapExtent

/**
 * A borrowed native handle, as an opaque address.
 *
 * The host owns whatever this points at; MapLibre Compose never frees, retains, or dereferences it.
 */
@JvmInline
public value class NativeHandle(public val address: Long) {
  public val isNull: Boolean
    get() = address == 0L

  override fun toString(): String = "NativeHandle(0x${address.toString(16)})"
}

/** Whether a texture's first row is its top or its bottom. */
internal enum class TextureOrigin {
  TOP_LEFT,
  BOTTOM_LEFT,
}

/**
 * A render target the host has allocated for MapLibre Native to render into.
 *
 * These are *borrowed* targets: the host allocates, recycles, and frees them, and MapLibre renders
 * into whichever one the current frame carries.
 */
internal sealed interface MlnFfiRenderTarget {
  /** The backend MapLibre must render with to use this target. */
  val backend: MapRenderBackend

  val extent: MapExtent

  /**
   * Identifies the underlying target object.
   *
   * A host must bump this any time the handles it reports stop referring to the same allocation —
   * after a reallocating resize, after surface loss, or when rotating through a pool — and must
   * keep the retired allocation readable until it has been asked to draw a different one.
   */
  val generation: Long
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
  /** `VkImageLayout` the host leaves [image] in before MapLibre renders. */
  val initialLayout: Int,
  /** `VkImageLayout` MapLibre must leave [image] in for the host to consume it. */
  val finalLayout: Int,
  /** Queue family owning [image] across the producer/consumer handoff. */
  val queueFamilyIndex: Int,
  override val extent: MapExtent,
  override val generation: Long,
) : MlnFfiRenderTarget {
  override val backend: MapRenderBackend
    get() = MapRenderBackend.VULKAN
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
    get() = MapRenderBackend.METAL
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
  /** `EGLContext` MapLibre's context should share objects with. */
  val shareContext: NativeHandle,
  /** `eglGetProcAddress`. */
  val getProcAddress: NativeHandle,
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
) : OpenGlContextHandles

/**
 * An OpenGL texture MapLibre renders into.
 *
 * Unlike Vulkan and Metal, OpenGL work is bound to whichever context is current on the calling
 * thread, so the target carries [makeContextCurrent] rather than a context handle alone.
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
    get() = MapRenderBackend.OPENGL
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
    get() = MapRenderBackend.OPENGL
}
