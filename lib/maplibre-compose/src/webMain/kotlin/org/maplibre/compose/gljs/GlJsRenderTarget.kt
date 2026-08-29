package org.maplibre.compose.gljs

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.webgl.WebGLRenderTarget
import kotlin.js.JsAny
import web.gl.WebGL2RenderingContext

/** The WebGL target that MapLibre renders into for a composited frame. */
internal interface GlJsRenderTarget : AutoCloseable {
  val gl: WebGL2RenderingContext
  val framebuffer: JsAny
  val widthPx: Int
  val heightPx: Int

  /** Clears state that Compose uses but MapLibre GL JS does not track. */
  fun prepareMapRender()

  /** Restores state used by another renderer after MapLibre GL JS renders. */
  fun finishMapRender()
}

internal class GlJsMapRenderState(private val gl: WebGL2RenderingContext) {
  private val fragmentTextureUnits =
    glGetNumber(gl, GL_MAX_TEXTURE_IMAGE_UNITS)?.toInt()
      ?: error("WebGL did not report its fragment texture unit count")

  fun prepare() {
    repeat(fragmentTextureUnits) { unit -> bindSamplerNone(gl, unit) }
    gl.disable(glEnum(GL_SCISSOR_TEST))
  }
}

/** Adapts Compose UI's WebGL target to the renderer-independent MapLibre surface contract. */
@OptIn(ExperimentalComposeUiApi::class)
internal class ComposeGlJsRenderTarget(private val target: WebGLRenderTarget) : GlJsRenderTarget {
  override val gl: WebGL2RenderingContext = jsUnsafeCast(target.webGLContext)
  private val mapRenderState = GlJsMapRenderState(gl)

  override val framebuffer: JsAny
    get() = jsUnsafeCast(target.framebuffer)

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
