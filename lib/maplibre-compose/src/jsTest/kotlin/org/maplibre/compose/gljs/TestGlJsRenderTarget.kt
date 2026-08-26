package org.maplibre.compose.gljs

import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin

private const val GL_TEXTURE_2D = 0x0DE1
private const val GL_RGBA8 = 0x8058

/** A manually adopted target used by [BrowserCompositingTest]. */
internal class TestGlJsRenderTarget(
  private val gpu: BrowserGpu,
  widthPx: Int,
  heightPx: Int,
) : GlJsRenderTarget {
  override var widthPx: Int = widthPx
    private set

  override var heightPx: Int = heightPx
    private set

  override val gl: dynamic = gpu.gl.asDynamic()
  private val mapRenderState = GlJsMapRenderState(gl)

  private val framebufferObject: dynamic
  private val depthStencil: dynamic
  private var texture: dynamic = null

  var image: Image
    private set

  override val framebuffer: Any
    get() = framebufferObject.unsafeCast<Any>()

  init {
    depthStencil = gl.createRenderbuffer()
    gl.bindRenderbuffer(gl.RENDERBUFFER, depthStencil)
    gl.renderbufferStorage(gl.RENDERBUFFER, gl.DEPTH24_STENCIL8, widthPx, heightPx)

    framebufferObject = gl.createFramebuffer()
    image = createImage(widthPx, heightPx)
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
      "MapLibre's test target is incomplete at ${widthPx}x$heightPx: $status"
    }
  }

  private fun createImage(widthPx: Int, heightPx: Int): Image {
    texture = gl.createTexture()
    gl.bindTexture(gl.TEXTURE_2D, texture)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, widthPx, heightPx, 0, gl.RGBA, gl.UNSIGNED_BYTE, null)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    val textureName = EmscriptenGl.registerTexture(texture.unsafeCast<Any>())
    val backendTexture =
      BackendTexture.makeGL(
        width = widthPx,
        height = heightPx,
        isMipmapped = false,
        textureId = textureName,
        textureTarget = GL_TEXTURE_2D,
        textureFormat = GL_RGBA8,
      )
    return Image.adoptTextureFrom(
      context = gpu.skia,
      backendTexture = backendTexture,
      origin = SurfaceOrigin.BOTTOM_LEFT,
      colorType = ColorType.RGBA_8888,
    )
  }

  override fun prepareMapRender() {
    mapRenderState.prepare()
  }

  override fun finishMapRender() {
    gpu.skia.resetGLAll()
  }

  fun resize(widthPx: Int, heightPx: Int) {
    image.close()
    this.widthPx = widthPx
    this.heightPx = heightPx

    image = createImage(widthPx, heightPx)
    gl.bindRenderbuffer(gl.RENDERBUFFER, depthStencil)
    gl.renderbufferStorage(gl.RENDERBUFFER, gl.DEPTH24_STENCIL8, widthPx, heightPx)
    gl.bindFramebuffer(gl.FRAMEBUFFER, framebufferObject)
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0)
    gl.framebufferRenderbuffer(
      gl.FRAMEBUFFER,
      gl.DEPTH_STENCIL_ATTACHMENT,
      gl.RENDERBUFFER,
      depthStencil,
    )
  }

  override fun close() {
    gl.deleteFramebuffer(framebufferObject)
    gl.deleteRenderbuffer(depthStencil)
    image.close()
  }
}
