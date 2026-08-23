package org.maplibre.compose.gljs

import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin

/**
 * The texture MapLibre GL JS renders into and Compose draws from, sized in physical pixels and
 * never resized in place. Everything here is allocated in the one context skiko created.
 */
internal class GlJsRenderTarget(
  val gl: dynamic,
  val widthPx: Int,
  val heightPx: Int,
  val generation: Long,
) : AutoCloseable {

  private val fragmentTextureUnits =
    (gl.getParameter(gl.MAX_TEXTURE_IMAGE_UNITS) as? Int)
      ?: error("WebGL did not report its fragment texture unit count")

  private val framebufferObject: dynamic
  private val depthStencil: dynamic

  /** Skia owns the texture behind it from the moment it is adopted. */
  val image: Image

  val framebuffer: Any
    get() = framebufferObject.unsafeCast<Any>()

  init {
    val texture = gl.createTexture()
    gl.bindTexture(gl.TEXTURE_2D, texture)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, widthPx, heightPx, 0, gl.RGBA, gl.UNSIGNED_BYTE, null)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    val textureName = EmscriptenGl.registerTexture(texture.unsafeCast<Any>())

    // MapLibre draws fills through the stencil buffer, so the target needs one.
    depthStencil = gl.createRenderbuffer()
    gl.bindRenderbuffer(gl.RENDERBUFFER, depthStencil)
    gl.renderbufferStorage(gl.RENDERBUFFER, gl.DEPTH24_STENCIL8, widthPx, heightPx)

    framebufferObject = gl.createFramebuffer()
    gl.bindFramebuffer(gl.FRAMEBUFFER, framebufferObject)
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0)
    gl.framebufferRenderbuffer(
      gl.FRAMEBUFFER,
      gl.DEPTH_STENCIL_ATTACHMENT,
      gl.RENDERBUFFER,
      depthStencil,
    )
    val status = gl.checkFramebufferStatus(gl.FRAMEBUFFER)
    gl.bindFramebuffer(gl.FRAMEBUFFER, null)
    check(status == gl.FRAMEBUFFER_COMPLETE) {
      "MapLibre's render target is incomplete at ${widthPx}x$heightPx: $status"
    }

    val context =
      checkNotNull(SkikoGpuBridge.directContext()) {
        "Compose's GPU context is not available: ${SkikoGpuBridge.diagnostic()}"
      }
    val backendTexture =
      BackendTexture.makeGL(
        width = widthPx,
        height = heightPx,
        isMipmapped = false,
        textureId = textureName,
        textureTarget = GL_TEXTURE_2D,
        textureFormat = GL_RGBA8,
      )
    image =
      Image.adoptTextureFrom(
        context = context,
        backendTexture = backendTexture,
        // GL renders bottom-up.
        origin = SurfaceOrigin.BOTTOM_LEFT,
        colorType = ColorType.RGBA_8888,
      )
  }

  /**
   * Removes sampler objects left by Skia. A sampler object overrides the filtering and wrapping on
   * MapLibre's textures, and MapLibre does not track sampler bindings in its WebGL state cache.
   */
  fun unbindSamplerObjects() {
    repeat(fragmentTextureUnits) { unit -> gl.bindSampler(unit, null) }
  }

  /** The texture is not deleted here: adoption handed it to Skia. */
  override fun close() {
    gl.deleteFramebuffer(framebufferObject)
    gl.deleteRenderbuffer(depthStencil)
    image.close()
  }
}
