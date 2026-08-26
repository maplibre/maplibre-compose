package org.maplibre.compose.gljs

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.webgl.WebGLRenderTarget

/** The WebGL target that MapLibre renders into for a composited frame. */
internal interface GlJsRenderTarget : AutoCloseable {
  val gl: dynamic
  val framebuffer: Any
  val widthPx: Int
  val heightPx: Int

  /** Clears state that Compose uses but MapLibre GL JS does not track. */
  fun prepareMapRender()

  /** Restores state used by another renderer after MapLibre GL JS renders. */
  fun finishMapRender()
}

internal class GlJsMapRenderState(private val gl: dynamic) {
  private val fragmentTextureUnits =
    (gl.getParameter(gl.MAX_TEXTURE_IMAGE_UNITS) as? Int)
      ?: error("WebGL did not report its fragment texture unit count")

  fun prepare() {
    repeat(fragmentTextureUnits) { unit -> gl.bindSampler(unit, null) }
    gl.disable(gl.SCISSOR_TEST)
  }
}

/** Adapts Compose UI's WebGL target to the renderer-independent MapLibre surface contract. */
@OptIn(ExperimentalComposeUiApi::class)
internal class ComposeGlJsRenderTarget(private val target: WebGLRenderTarget) : GlJsRenderTarget {
  override val gl: dynamic = target.webGLContext.asDynamic()
  private val mapRenderState = GlJsMapRenderState(gl)

  override val framebuffer: Any
    get() = target.framebuffer.unsafeCast<Any>()

  override val widthPx: Int
    get() = target.size.width

  override val heightPx: Int
    get() = target.size.height

  override fun prepareMapRender() {
    mapRenderState.prepare()
  }

  override fun finishMapRender() = Unit

  override fun close() = Unit
}
