package org.maplibre.compose.desktop.skiko

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
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
import org.maplibre.compose.desktop.OpenGlTextureTarget
import org.maplibre.compose.desktop.TextureOrigin
import org.maplibre.compose.desktop.skiko.SkikoReflection.getField
import org.maplibre.compose.desktop.skiko.SkikoReflection.invokeDeclaredNoArg
import org.maplibre.compose.desktop.skiko.SkikoReflection.staticInvoke

/**
 * How many snapshots to hold alive after handing them to Compose.
 *
 * Compose records draw commands and replays them later, so an image closed immediately after
 * `drawImageRect` can be sampled after it is gone. Retaining a short ring keeps recorded frames
 * valid without unbounded growth.
 */
private const val RETAINED_IMAGE_COUNT = 8

/**
 * Draws MapLibre's OpenGL texture into Compose's Skia canvas on Linux.
 *
 * Compose owns the GL context and Skia's [DirectContext]; this wraps the texture MapLibre rendered
 * into as a Skia surface and composites it.
 */
internal object SkikoOpenGlPresenter {
  private val presenters = mutableMapOf<Int, TexturePresenter>()

  /** Runs [action] with Compose's OpenGL context current, on the AWT event thread. */
  fun <T> withContext(action: () -> T): T = SkikoReflection.onEdt {
    val layer = SkikoReflection.requireSkiaLayer()
    val redrawer =
      SkikoReflection.requireRedrawer(layer, SkikoReflection.LINUX_OPENGL_REDRAWER_CLASS)
    val backedLayer =
      layer.getField("backedLayer")
        ?: throw DesktopHostException("${SkikoReflection.SKIA_LAYER_CLASS}.backedLayer was null")
    val context =
      redrawer.getField("context") as? Long
        ?: throw DesktopHostException(
          "${SkikoReflection.LINUX_OPENGL_REDRAWER_CLASS}.context was null"
        )
    check(context != 0L) { "${SkikoReflection.LINUX_OPENGL_REDRAWER_CLASS}.context was zero" }

    val surfaceHelpers = Class.forName(SkikoReflection.AWT_LINUX_DRAWING_SURFACE_HELPERS_CLASS)
    val drawingSurface = surfaceHelpers.staticInvoke("lockLinuxDrawingSurface", backedLayer)
    try {
      Class.forName(SkikoReflection.LINUX_OPENGL_REDRAWER_HELPERS_CLASS)
        .staticInvoke("access\$makeCurrent", drawingSurface, context)
      ensureCapabilities()
      action()
    } finally {
      surfaceHelpers.staticInvoke("unlockLinuxDrawingSurface", drawingSurface)
    }
  }

  fun draw(scope: DrawScope, target: OpenGlTextureTarget): Boolean {
    var drew = false
    scope.drawIntoCanvas { composeCanvas ->
      val context = findDirectContext() ?: return@drawIntoCanvas
      ensureCapabilities()
      val presenter =
        presenters.getOrPut(target.textureName) { TexturePresenter(target.textureName) }
      presenter.draw(
        composeCanvas.nativeCanvas,
        context,
        target,
        scope.size.width,
        scope.size.height,
      )
      drew = true
    }
    return drew
  }

  /** Drops the Skia wrapper for [textureName], which must happen before the texture is deleted. */
  fun forget(textureName: Int) {
    presenters.remove(textureName)?.close()
  }

  fun close() {
    val all = presenters.values.toList()
    presenters.clear()
    all.forEach { it.close() }
  }

  private fun findDirectContext(): DirectContext? = SkikoReflection.onEdt {
    val layer = SkikoReflection.requireSkiaLayer()
    val redrawer =
      SkikoReflection.requireRedrawer(layer, SkikoReflection.LINUX_OPENGL_REDRAWER_CLASS)
    val handler =
      SkikoReflection.requireContextHandler(redrawer, SkikoReflection.LINUX_OPENGL_REDRAWER_CLASS)
    (handler.getField("context") as? DirectContext)
      ?: run {
        handler.invokeDeclaredNoArg("initContext")
        (handler.getField("context") as? DirectContext)
          ?: handler.invokeDeclaredNoArg("getContext") as? DirectContext
      }
  }

  private class TexturePresenter(private val textureName: Int) : AutoCloseable {
    private var contextIdentity = 0
    private var width = 0
    private var height = 0
    private var origin = TextureOrigin.TOP_LEFT
    private var framebuffer = 0
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null
    private val retainedImages = ArrayDeque<Image>()

    fun draw(
      canvas: org.jetbrains.skia.Canvas,
      context: DirectContext,
      target: OpenGlTextureTarget,
      destinationWidth: Float,
      destinationHeight: Float,
    ) {
      ensureSurface(context, target)
      val currentSurface =
        surface ?: throw DesktopHostException("Skia could not wrap OpenGL texture $textureName")

      // MapLibre left arbitrary GL state behind; Skia caches its own view of that state and will
      // render incorrectly unless told to re-read it.
      context.resetGLAll()
      currentSurface.notifyContentWillChange(ContentChangeMode.DISCARD)
      val image = currentSurface.makeImageSnapshot()
      retain(image)
      canvas.drawImageRect(
        image = image,
        src = Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        dst = Rect.makeWH(destinationWidth, destinationHeight),
        samplingMode = SamplingMode.LINEAR,
        paint = null,
        strict = true,
      )
    }

    private fun ensureSurface(context: DirectContext, target: OpenGlTextureTarget) {
      val nextIdentity = System.identityHashCode(context)
      if (
        surface != null &&
          renderTarget != null &&
          framebuffer != 0 &&
          contextIdentity == nextIdentity &&
          width == target.extent.physicalWidth &&
          height == target.extent.physicalHeight &&
          origin == target.origin
      ) {
        return
      }

      closeGpuResources()
      contextIdentity = nextIdentity
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
          ?: throw DesktopHostException(
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

    private fun retain(image: Image) {
      retainedImages.addLast(image)
      while (retainedImages.size > RETAINED_IMAGE_COUNT) retainedImages.removeFirst().close()
    }

    override fun close() {
      closeGpuResources()
      contextIdentity = 0
      width = 0
      height = 0
      origin = TextureOrigin.TOP_LEFT
    }

    private fun closeGpuResources() {
      while (retainedImages.isNotEmpty()) retainedImages.removeFirst().close()
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

private fun TextureOrigin.toSkiaOrigin(): SurfaceOrigin =
  when (this) {
    TextureOrigin.TOP_LEFT -> SurfaceOrigin.TOP_LEFT
    TextureOrigin.BOTTOM_LEFT -> SurfaceOrigin.BOTTOM_LEFT
  }

internal fun ensureCapabilities() =
  runCatching { GL.getCapabilities() }.getOrNull() ?: GL.createCapabilities()

internal fun checkGl(operation: String) {
  val error = glGetError()
  check(error == GL_NO_ERROR) { "$operation failed with GL error 0x${error.toString(16)}" }
}
