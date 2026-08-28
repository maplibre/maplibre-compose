package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
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
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.TextureOrigin

/**
 * Draws MapLibre's OpenGL texture into a Compose Skia canvas.
 *
 * Every method runs on the GPU thread with Compose's GL context current. [backend] contains the
 * context-specific GL entry points and the Skia presentation path.
 */
internal class OpenGlPresenter private constructor(private val backend: OpenGlPresenterBackend) :
  AutoCloseable {
  private val presenters = mutableMapOf<Int, TexturePresenter>()

  fun draw(
    scope: DrawScope,
    skiaContext: DirectContext,
    target: OpenGlTextureTarget,
    destination: MlnFfiMapDestination,
    completion: ComposeFrameCompletion,
  ): Boolean {
    var drew = false
    scope.drawIntoCanvas { composeCanvas ->
      backend.ensureUsable()
      val presenter =
        presenters.getOrPut(target.textureName) {
          TexturePresenter(target.textureName, backend)
        }
      presenter.draw(
        composeCanvas.skiaCanvas,
        skiaContext,
        target,
        destination,
      )
      completion.frameRecorded(presenter::preserveFrame)
      drew = true
    }
    return drew
  }

  /** Drops the Skia wrapper before its texture is released. */
  fun forget(textureName: Int) {
    presenters.remove(textureName)?.close()
  }

  /** Drops wrappers whose names belonged to an OpenGL context that no longer exists. */
  fun abandon() {
    val all = presenters.values.toList()
    presenters.clear()
    all.forEach { it.abandon() }
  }

  override fun close() {
    val all = presenters.values.toList()
    presenters.clear()
    all.forEach { it.close() }
  }

  private class TexturePresenter(
    private val textureName: Int,
    private val backend: OpenGlPresenterBackend,
  ) : AutoCloseable {
    private var width = 0
    private var height = 0
    private var origin = TextureOrigin.TOP_LEFT
    private var framebuffer = 0
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null

    fun draw(
      canvas: Canvas,
      context: DirectContext,
      target: OpenGlTextureTarget,
      destination: MlnFfiMapDestination,
    ) {
      ensureSurface(context, target)
      val currentSurface =
        surface ?: throw MlnFfiHostException("Skia could not wrap OpenGL texture $textureName")

      // MapLibre leaves arbitrary GL state behind. Skia must refresh its cached view of that state.
      context.resetGLAll()
      currentSurface.notifyContentWillChange(ContentChangeMode.DISCARD)
      backend.draw(currentSurface, canvas, destination)
    }

    fun preserveFrame() {
      surface?.notifyContentWillChange(ContentChangeMode.RETAIN)
    }

    private fun ensureSurface(context: DirectContext, target: OpenGlTextureTarget) {
      if (
        surface != null &&
          renderTarget != null &&
          framebuffer != 0 &&
          width == target.extent.physicalWidth &&
          height == target.extent.physicalHeight &&
          origin == target.origin
      ) {
        return
      }

      closeGpuResources()
      width = target.extent.physicalWidth
      height = target.extent.physicalHeight
      origin = target.origin
      framebuffer = backend.createFramebuffer(target)
      renderTarget =
        BackendRenderTarget.makeGL(
          width = width,
          height = height,
          sampleCnt = 0,
          stencilBits = 0,
          fbId = framebuffer,
          fbFormat = FramebufferFormat.GR_GL_RGBA8,
        )
      surface =
        Surface.makeFromBackendRenderTarget(
          context = context,
          rt = checkNotNull(renderTarget),
          origin = origin.toSkiaOrigin(),
          colorFormat = SurfaceColorFormat.RGBA_8888,
          colorSpace = null,
          surfaceProps = null,
        )
          ?: throw MlnFfiHostException(
            "Skia could not wrap OpenGL framebuffer $framebuffer for texture $textureName"
          )
    }

    override fun close() {
      closeGpuResources()
      resetMetadata()
    }

    fun abandon() {
      surface?.close()
      surface = null
      renderTarget?.close()
      renderTarget = null
      framebuffer = 0
      resetMetadata()
    }

    private fun closeGpuResources() {
      surface?.close()
      surface = null
      renderTarget?.close()
      renderTarget = null
      if (framebuffer != 0) {
        runCatching { backend.deleteFramebuffer(framebuffer) }
        framebuffer = 0
      }
    }

    private fun resetMetadata() {
      width = 0
      height = 0
      origin = TextureOrigin.TOP_LEFT
    }
  }

  companion object {
    fun native(): OpenGlPresenter = OpenGlPresenter(DesktopOpenGlPresenterBackend)

    fun angle(): OpenGlPresenter = OpenGlPresenter(AngleOpenGlPresenterBackend)
  }
}

/** The GL and Skia operations that differ between desktop OpenGL and ANGLE/GLES. */
private interface OpenGlPresenterBackend {
  fun ensureUsable()

  fun createFramebuffer(target: OpenGlTextureTarget): Int

  fun deleteFramebuffer(framebuffer: Int)

  fun draw(
    surface: Surface,
    canvas: Canvas,
    destination: MlnFfiMapDestination,
  )
}

private object DesktopOpenGlPresenterBackend : OpenGlPresenterBackend {
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

  override fun draw(
    surface: Surface,
    canvas: Canvas,
    destination: MlnFfiMapDestination,
  ) {
    surface.makeImageSnapshot().use { image ->
      canvas.drawImageRect(
        image = image,
        src = Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        dst =
          Rect.makeLTRB(
            destination.left.toFloat(),
            destination.top.toFloat(),
            destination.right.toFloat(),
            destination.bottom.toFloat(),
          ),
        samplingMode = SamplingMode.LINEAR,
        paint = null,
        strict = true,
      )
    }
  }
}

private object AngleOpenGlPresenterBackend : OpenGlPresenterBackend {
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

  override fun draw(
    surface: Surface,
    canvas: Canvas,
    destination: MlnFfiMapDestination,
  ) {
    canvas.save()
    try {
      surface.draw(
        canvas,
        destination.left,
        destination.top,
        SamplingMode.LINEAR,
        null,
      )
    } finally {
      canvas.restore()
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
