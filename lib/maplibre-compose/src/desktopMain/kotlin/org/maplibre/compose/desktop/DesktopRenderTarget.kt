package org.maplibre.compose.desktop

import androidx.compose.runtime.Immutable

/**
 * A borrowed native handle, as an opaque address.
 *
 * The host owns whatever this points at. MapLibre Compose only forwards it into MapLibre Native and
 * never frees, retains, or dereferences it, so the host is free to recycle the underlying object
 * once it reports a new [DesktopRenderTarget.generation].
 */
@JvmInline
public value class NativeHandle(public val address: Long) {
  /** Whether this handle is null. */
  public val isNull: Boolean
    get() = address == 0L

  override fun toString(): String = "NativeHandle(0x${address.toString(16)})"
}

/** Whether a texture's first row is its top or its bottom. */
public enum class TextureOrigin {
  TOP_LEFT,
  BOTTOM_LEFT,
}

/**
 * A render target the host has allocated for MapLibre Native to render into.
 *
 * These are *borrowed* targets: the host allocates, recycles, and frees them, and MapLibre renders
 * into whichever one the current frame carries.
 */
public sealed interface DesktopRenderTarget {
  /** The backend MapLibre must render with to use this target. */
  public val backend: MapRenderBackend

  /** The size of this target. */
  public val extent: DesktopMapExtent

  /**
   * Identifies the underlying target object.
   *
   * The session re-attaches its render session whenever this changes, so a host must bump it any
   * time the handles it reports stop referring to the same allocation — after a resize that
   * reallocates, after surface loss, or when rotating through a pool.
   *
   * Bumping this does not license freeing the old allocation immediately. MapLibre only produces an
   * update when it has one, so the frames just after a resize routinely skip, and the surface
   * presents the last target that *was* rendered into rather than a blank one — which may be the
   * target this generation replaced. A host must therefore keep a retired target readable until it
   * has been asked to draw a different one. Freeing on the bump instead is not a leak-shaped bug:
   * on macOS it hands Skia a released `MTLTexture` and traps inside `CFRetain`.
   */
  public val generation: Long
}

/** Vulkan instance and device handles MapLibre needs to render into a Vulkan target. */
@Immutable
public data class VulkanContextHandles(
  /** `VkInstance`. */
  public val instance: NativeHandle,
  /** `VkPhysicalDevice`. */
  public val physicalDevice: NativeHandle,
  /** `VkDevice`. */
  public val device: NativeHandle,
  /** `VkQueue` for graphics work. */
  public val graphicsQueue: NativeHandle,
  /** Queue family index of [graphicsQueue]. */
  public val graphicsQueueFamilyIndex: Int,
  /** `PFN_vkGetInstanceProcAddr`. */
  public val getInstanceProcAddr: NativeHandle,
  /** `PFN_vkGetDeviceProcAddr`. */
  public val getDeviceProcAddr: NativeHandle,
)

/** A `VkImage` MapLibre renders into. */
@Immutable
public data class VulkanImageTarget(
  /** The Vulkan context owning [image]. */
  public val context: VulkanContextHandles,
  /** `VkImage`. */
  public val image: NativeHandle,
  /** `VkImageView` over [image]. */
  public val imageView: NativeHandle,
  /** `VkFormat` of [image]. */
  public val format: Int,
  /** `VkImageLayout` the host leaves [image] in before MapLibre renders. */
  public val initialLayout: Int,
  /** `VkImageLayout` MapLibre must leave [image] in for the host to consume it. */
  public val finalLayout: Int,
  /** Queue family owning [image] across the producer/consumer handoff. */
  public val queueFamilyIndex: Int,
  override val extent: DesktopMapExtent,
  override val generation: Long,
) : DesktopRenderTarget {
  override val backend: MapRenderBackend
    get() = MapRenderBackend.VULKAN
}

/** An `id<MTLTexture>` MapLibre renders into. */
@Immutable
public data class MetalTextureTarget(
  /** `id<MTLTexture>`. */
  public val texture: NativeHandle,
  /** `MTLPixelFormat` of [texture]. */
  public val pixelFormat: Long,
  /** Row order of [texture]. */
  public val origin: TextureOrigin,
  override val extent: DesktopMapExtent,
  override val generation: Long,
) : DesktopRenderTarget {
  override val backend: MapRenderBackend
    get() = MapRenderBackend.METAL
}

/** Platform context handles MapLibre needs to render into an OpenGL target. */
public sealed interface OpenGlContextHandles

/** EGL context handles, used on Linux. */
@Immutable
public data class EglContextHandles(
  /** `EGLDisplay`. */
  public val display: NativeHandle,
  /** `EGLConfig`. */
  public val config: NativeHandle,
  /** `EGLContext` MapLibre's context should share objects with. */
  public val shareContext: NativeHandle,
  /** `eglGetProcAddress`. */
  public val getProcAddress: NativeHandle,
) : OpenGlContextHandles

/** WGL context handles, used on Windows. */
@Immutable
public data class WglContextHandles(
  /** `HDC`. */
  public val deviceContext: NativeHandle,
  /** `HGLRC` MapLibre's context should share objects with. */
  public val shareContext: NativeHandle,
  /** `wglGetProcAddress`. */
  public val getProcAddress: NativeHandle,
) : OpenGlContextHandles

/**
 * An OpenGL texture MapLibre renders into.
 *
 * Unlike Vulkan and Metal, OpenGL work is bound to whichever context is current on the calling
 * thread, so the target carries [makeContextCurrent] rather than a context handle alone.
 */
@Immutable
public data class OpenGlTextureTarget(
  /** Platform context handles for the context [textureName] belongs to. */
  public val context: OpenGlContextHandles,
  /** GL texture name. */
  public val textureName: Int,
  /** GL texture target, such as `GL_TEXTURE_2D`. */
  public val textureTarget: Int,
  /** GL sized internal format of the texture. */
  public val format: Int,
  /** Row order of the texture. */
  public val origin: TextureOrigin,
  /**
   * Makes the context owning this texture current on the calling thread.
   *
   * The session calls this before any OpenGL work touching the target. It must be safe to call when
   * the context is already current.
   */
  public val makeContextCurrent: () -> Unit,
  override val extent: DesktopMapExtent,
  override val generation: Long,
) : DesktopRenderTarget {
  override val backend: MapRenderBackend
    get() = MapRenderBackend.OPENGL
}
