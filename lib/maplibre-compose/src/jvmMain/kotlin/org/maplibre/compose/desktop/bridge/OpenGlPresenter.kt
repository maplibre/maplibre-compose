package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glFinish
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
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.TextureOrigin

/**
 * Draws MapLibre's OpenGL texture into Compose's Skia canvas on Linux.
 *
 * Compose owns the GL context and Skia's [DirectContext]; this wraps the texture MapLibre rendered
 * into as a Skia surface and composites it.
 *
 * Every method must be called on the GPU thread with Compose's GL context current — see
 * [withOpenGlContext] — because the Skia objects belong to that context and GL calls go wherever
 * the calling thread's context points.
 */
internal class OpenGlPresenter : AutoCloseable {
  private val presenters = mutableMapOf<Int, TexturePresenter>()

  fun draw(
    scope: DrawScope,
    skiaContext: DirectContext,
    target: OpenGlTextureTarget,
    completion: ComposeFrameCompletion,
  ): Boolean {
    var drew = false
    scope.drawIntoCanvas { composeCanvas ->
      ensureCapabilities()
      val presenter =
        presenters.getOrPut(target.textureName) { TexturePresenter(target.textureName) }
      presenter.draw(
        composeCanvas.skiaCanvas,
        skiaContext,
        target,
        scope.size.width,
        scope.size.height,
      )
      completion.frameRecorded(presenter::preserveFrame)
      drew = true
    }
    return drew
  }

  /**
   * Drops the Skia wrapper for a texture, which must happen before the texture itself is released.
   */
  fun forget(textureName: Int) {
    presenters.remove(textureName)?.close()
  }

  /** Drops wrappers whose OpenGL names belonged to a context that no longer exists. */
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

  private class TexturePresenter(private val textureName: Int) : AutoCloseable {
    private var width = 0
    private var height = 0
    private var origin = TextureOrigin.TOP_LEFT
    private var framebuffer = 0
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var ownedCopy: Surface? = null

    fun draw(
      canvas: org.jetbrains.skia.Canvas,
      context: DirectContext,
      target: OpenGlTextureTarget,
      destinationWidth: Float,
      destinationHeight: Float,
    ) {
      ensureSurface(context, target)
      val currentSurface =
        surface ?: throw MlnFfiHostException("Skia could not wrap OpenGL texture $textureName")

      // MapLibre left arbitrary GL state behind; Skia caches its own view of that state and will
      // render incorrectly unless told to re-read it.
      context.resetGLAll()
      // Vulkan already waited idle; this is the GL-side barrier so the import is visible to Skia.
      glFinish()
      currentSurface.notifyContentWillChange(ContentChangeMode.DISCARD)
      currentSurface.makeImageSnapshot().use { imported ->
        // A snapshot of an EXT_memory_object import can share that allocation. Compose records
        // the image into a Picture and replays it after MapLibre writes the same memory. Copying
        // into a surface Skia owns keeps the recorded pixels when the import is reused.
        val copy = ownedCopySurface(context, imported.width, imported.height)
        copy.notifyContentWillChange(ContentChangeMode.RETAIN)
        copy.canvas.drawImageRect(
          image = imported,
          src = Rect.makeWH(imported.width.toFloat(), imported.height.toFloat()),
          dst = Rect.makeWH(imported.width.toFloat(), imported.height.toFloat()),
          samplingMode = SamplingMode.LINEAR,
          paint = null,
          strict = true,
        )
        copy.flushAndSubmit()
        copy.makeImageSnapshot().use { owned ->
          canvas.drawImageRect(
            image = owned,
            src = Rect.makeWH(owned.width.toFloat(), owned.height.toFloat()),
            dst = Rect.makeWH(destinationWidth, destinationHeight),
            samplingMode = SamplingMode.LINEAR,
            paint = null,
            strict = true,
          )
        }
      }
    }

    fun preserveFrame() {
      ownedCopy?.notifyContentWillChange(ContentChangeMode.RETAIN)
    }

    private fun ownedCopySurface(context: DirectContext, copyWidth: Int, copyHeight: Int): Surface {
      val existing = ownedCopy
      if (existing != null && existing.width == copyWidth && existing.height == copyHeight) {
        return existing
      }
      existing?.close()
      val created =
        Surface.makeRenderTarget(
          context,
          false,
          ImageInfo(copyWidth, copyHeight, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
        )
      ownedCopy = created
      return created
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
      framebuffer = createFramebuffer(target)
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

    private fun createFramebuffer(target: OpenGlTextureTarget): Int {
      ensureCapabilities()
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
          "OpenGL framebuffer for texture $textureName is incomplete: 0x${status.toString(16)}"
        }
        return next
      } catch (error: RuntimeException) {
        runCatching { glDeleteFramebuffers(next) }
        throw error
      } finally {
        glBindFramebuffer(GL_FRAMEBUFFER, previous)
      }
    }

    override fun close() {
      closeGpuResources()
      width = 0
      height = 0
      origin = TextureOrigin.TOP_LEFT
    }

    fun abandon() {
      ownedCopy?.close()
      ownedCopy = null
      surface?.close()
      surface = null
      renderTarget?.close()
      renderTarget = null
      framebuffer = 0
      width = 0
      height = 0
      origin = TextureOrigin.TOP_LEFT
    }

    private fun closeGpuResources() {
      ownedCopy?.close()
      ownedCopy = null
      surface?.close()
      surface = null
      renderTarget?.close()
      renderTarget = null
      if (framebuffer != 0) {
        runCatching {
          ensureCapabilities()
          glDeleteFramebuffers(framebuffer)
        }
        framebuffer = 0
      }
    }
  }
}

internal fun ensureCapabilities() =
  runCatching { GL.getCapabilities() }.getOrNull() ?: GL.createCapabilities()

/**
 * Drops errors left by code that used this shared context before the bridge entered it.
 *
 * OpenGL's error flag is sticky and belongs to the context, not the caller. Without an explicit
 * boundary, [checkGl] can blame the bridge's first call for an error produced earlier by Compose or
 * its window host.
 */
internal fun clearGlErrors() {
  while (glGetError() != GL_NO_ERROR) {
    // Reading the flag is what clears it; there is nothing to do with the value.
  }
}

internal fun checkGl(operation: String) {
  val error = glGetError()
  check(error == GL_NO_ERROR) { "$operation failed with GL error 0x${error.toString(16)}" }
}
