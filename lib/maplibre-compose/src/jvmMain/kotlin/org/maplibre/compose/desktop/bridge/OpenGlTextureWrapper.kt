package org.maplibre.compose.desktop.bridge

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.SurfaceColorFormat
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError
import org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glCheckFramebufferStatus
import org.lwjgl.opengl.GL30.glDeleteFramebuffers
import org.lwjgl.opengl.GL30.glFramebufferTexture2D
import org.lwjgl.opengl.GL30.glGenFramebuffers
import org.lwjgl.opengl.GL30.glGetInteger
import org.maplibre.compose.mlnffi.OpenGlTextureTarget

/**
 * Wraps an OpenGL texture for [SkiaTexturePresenter] by attaching it to a framebuffer Skia renders
 * through. Every call runs with Compose's GL context current.
 */
internal sealed class OpenGlTextureWrapper : SkiaTextureWrapper<OpenGlTextureTarget> {
  override fun key(target: OpenGlTextureTarget): Long = target.textureName.toLong()

  override fun layout(target: OpenGlTextureTarget) =
    SkiaTextureLayout(target.extent, SurfaceColorFormat.RGBA_8888, target.origin)

  override fun wrap(target: OpenGlTextureTarget, layout: SkiaTextureLayout): WrappedRenderTarget {
    val framebuffer = createFramebuffer(target)
    try {
      return WrappedRenderTarget(
        BackendRenderTarget.makeGL(
          width = layout.width,
          height = layout.height,
          sampleCnt = 0,
          stencilBits = 0,
          fbId = framebuffer,
          fbFormat = FramebufferFormat.GR_GL_RGBA8,
        ),
        "OpenGL framebuffer $framebuffer for texture ${target.textureName}",
      ) {
        deleteFramebuffer(framebuffer)
      }
    } catch (error: RuntimeException) {
      runCatching { deleteFramebuffer(framebuffer) }
      throw error
    }
  }

  override fun beforeDraw(context: DirectContext) {
    ensureUsable()
    // MapLibre leaves arbitrary GL state behind. Skia must refresh its cached view of that state.
    context.resetGLAll()
  }

  protected abstract fun ensureUsable()

  protected abstract fun createFramebuffer(target: OpenGlTextureTarget): Int

  protected abstract fun deleteFramebuffer(framebuffer: Int)

  /** Desktop OpenGL through LWJGL. */
  data object Native : OpenGlTextureWrapper() {
    override fun ensureUsable() {
      ensureCapabilities()
    }

    override fun createFramebuffer(target: OpenGlTextureTarget): Int {
      ensureUsable()
      val previous = glGetInteger(GL_FRAMEBUFFER_BINDING)
      val next = glGenFramebuffers()
      try {
        glBindFramebuffer(GL_FRAMEBUFFER, next)
        glFramebufferTexture2D(
          GL_FRAMEBUFFER,
          GL_COLOR_ATTACHMENT0,
          target.textureTarget,
          target.textureName,
          0,
        )
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        checkGl("glFramebufferTexture2D")
        check(status == GL_FRAMEBUFFER_COMPLETE) {
          "OpenGL framebuffer for texture ${target.textureName} is incomplete: " +
            "0x${status.toString(16)}"
        }
        return next
      } catch (error: RuntimeException) {
        runCatching { glDeleteFramebuffers(next) }
        throw error
      } finally {
        glBindFramebuffer(GL_FRAMEBUFFER, previous)
      }
    }

    override fun deleteFramebuffer(framebuffer: Int) {
      ensureUsable()
      glDeleteFramebuffers(framebuffer)
    }
  }

  /** GLES through ANGLE, whose entry points LWJGL does not bind. */
  data object Angle : OpenGlTextureWrapper() {
    override fun ensureUsable() {
      check(AngleGl.isUsable()) { "Compose's ANGLE context has no usable GLES entry points" }
    }

    override fun createFramebuffer(target: OpenGlTextureTarget): Int {
      ensureUsable()
      clearAngleGlErrors()
      val previous = AngleGl.getInteger(GL_FRAMEBUFFER_BINDING)
      val next = AngleGl.genFramebuffers()
      try {
        AngleGl.bindFramebuffer(GL_FRAMEBUFFER, next)
        AngleGl.framebufferTexture2D(
          GL_FRAMEBUFFER,
          GL_COLOR_ATTACHMENT0,
          target.textureTarget,
          target.textureName,
          0,
        )
        val status = AngleGl.checkFramebufferStatus(GL_FRAMEBUFFER)
        checkAngleGl("glFramebufferTexture2D")
        check(status == GL_FRAMEBUFFER_COMPLETE) {
          "ANGLE framebuffer for texture ${target.textureName} is incomplete: " +
            "0x${status.toString(16)}"
        }
        return next
      } catch (error: RuntimeException) {
        runCatching { AngleGl.deleteFramebuffers(next) }
        throw error
      } finally {
        AngleGl.bindFramebuffer(GL_FRAMEBUFFER, previous)
      }
    }

    override fun deleteFramebuffer(framebuffer: Int) {
      if (AngleGl.isUsable()) AngleGl.deleteFramebuffers(framebuffer)
    }
  }
}

internal fun ensureCapabilities() =
  runCatching { GL.getCapabilities() }.getOrNull() ?: GL.createCapabilities()

/** Clears errors left by earlier users of the current desktop OpenGL context. */
internal fun clearGlErrors() {
  while (glGetError() != GL_NO_ERROR) {
    // Reading the flag clears it.
  }
}

internal fun checkGl(operation: String) {
  val error = glGetError()
  check(error == GL_NO_ERROR) { "$operation failed with GL error 0x${error.toString(16)}" }
}

private fun checkAngleGl(operation: String) {
  val error = AngleGl.getError()
  check(error == GL_NO_ERROR) { "$operation failed with GL error 0x${error.toString(16)}" }
}

/** Clears errors left by earlier users of the current ANGLE/GLES context. */
private fun clearAngleGlErrors() {
  while (AngleGl.getError() != GL_NO_ERROR) {
    // Reading the flag clears it.
  }
}
