package org.maplibre.compose.mlnffi

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.awt.EventQueue
import java.lang.invoke.MethodHandles
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Surface
import org.jetbrains.skia.makeGLWithInterface
import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL10.EGL_ALPHA_SIZE
import org.lwjgl.egl.EGL10.EGL_BLUE_SIZE
import org.lwjgl.egl.EGL10.EGL_GREEN_SIZE
import org.lwjgl.egl.EGL10.EGL_HEIGHT
import org.lwjgl.egl.EGL10.EGL_NONE
import org.lwjgl.egl.EGL10.EGL_NO_CONTEXT
import org.lwjgl.egl.EGL10.EGL_NO_DISPLAY
import org.lwjgl.egl.EGL10.EGL_NO_SURFACE
import org.lwjgl.egl.EGL10.EGL_PBUFFER_BIT
import org.lwjgl.egl.EGL10.EGL_RED_SIZE
import org.lwjgl.egl.EGL10.EGL_SURFACE_TYPE
import org.lwjgl.egl.EGL10.EGL_WIDTH
import org.lwjgl.egl.EGL10.eglChooseConfig
import org.lwjgl.egl.EGL10.eglCreateContext
import org.lwjgl.egl.EGL10.eglCreatePbufferSurface
import org.lwjgl.egl.EGL10.eglDestroyContext
import org.lwjgl.egl.EGL10.eglDestroySurface
import org.lwjgl.egl.EGL10.eglGetDisplay
import org.lwjgl.egl.EGL10.eglGetError
import org.lwjgl.egl.EGL10.eglInitialize
import org.lwjgl.egl.EGL10.eglMakeCurrent
import org.lwjgl.egl.EGL10.eglTerminate
import org.lwjgl.egl.EGL10.neglGetProcAddress
import org.lwjgl.egl.EGL12.EGL_RENDERABLE_TYPE
import org.lwjgl.egl.EGL12.eglBindAPI
import org.lwjgl.egl.EGL14.EGL_DEFAULT_DISPLAY
import org.lwjgl.egl.EGL14.EGL_OPENGL_API
import org.lwjgl.egl.EGL14.EGL_OPENGL_BIT
import org.lwjgl.opengl.GL
import org.lwjgl.system.APIUtil.apiCreateCIF
import org.lwjgl.system.Callback
import org.lwjgl.system.CallbackI
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.MemoryUtil.memGetAddress
import org.lwjgl.system.MemoryUtil.memPutAddress
import org.lwjgl.system.Pointer.POINTER_SIZE
import org.lwjgl.system.libffi.LibFFI.ffi_type_pointer
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_LOCAL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_NOW
import org.lwjgl.system.macosx.DynamicLinkLoader.dlclose
import org.lwjgl.system.macosx.DynamicLinkLoader.dlopen
import org.lwjgl.system.macosx.DynamicLinkLoader.dlsym
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.desktop.Direct3D12ComposeGpuContext
import org.maplibre.compose.desktop.MetalComposeGpuContext
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.desktop.bridge.ComposeMapHostFactory
import org.maplibre.compose.desktop.bridge.MapRendererThread
import org.maplibre.compose.desktop.bridge.ObjectiveC
import org.maplibre.compose.desktop.bridge.currentContext
import org.maplibre.compose.desktop.bridge.requireContext
import org.maplibre.compose.desktop.bridge.withOpenGlContext
import org.maplibre.compose.desktop.onGpuThread
import org.maplibre.compose.desktop.skiko.AwtComposeMapHost
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

internal class ProductionBridgeTestRenderDriver
private constructor(
  private val environment: DesktopTestGpuEnvironment,
  private val bridge: MlnFfiMapHost,
) : FfiTestRenderDriver, MlnFfiMapHost by bridge {
  override fun acquireFrame(
    frameId: Long,
    extent: MapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrameAcquisition = environment.withContext {
    bridge.acquireFrame(frameId, extent, presentationTimeNanos)
  }

  override fun present(target: MlnFfiRenderTarget): Boolean = environment.present(bridge, target)

  override fun draw(scope: DrawScope, target: MlnFfiRenderTarget): Boolean = present(target)

  override fun readPixel(x: Int, y: Int): RgbaPixel = environment.readPixel(x, y)

  override fun close() {
    try {
      bridge.close()
    } finally {
      environment.close()
    }
  }

  companion object {
    fun create(): ProductionBridgeTestRenderDriver {
      val runtimeBackend =
        Maplibre.supportedRenderBackends().singleOrNull()
          ?: error(
            "Desktop bridge tests require exactly one packaged runtime backend; found " +
              Maplibre.supportedRenderBackends().joinToString().ifEmpty { "none" }
          )
      val producer =
        when (runtimeBackend) {
          RenderBackend.METAL -> MapRenderBackend.METAL
          RenderBackend.VULKAN -> MapRenderBackend.VULKAN
          else -> error("No production Desktop bridge for packaged runtime $runtimeBackend")
        }
      val environment = DesktopTestGpuEnvironment.create()
      return try {
        val factory = ComposeMapHostFactory(environment.gpuHost)
        val backends =
          factory.bridges.singleOrNull { it.producer == producer }
            ?: error("${factory.description} cannot bridge packaged runtime $producer")
        val bridge =
          when (val result = factory.create(backends)) {
            is MlnFfiMapHostResult.Created -> result.host
            is MlnFfiMapHostResult.Failed ->
              throw IllegalStateException(result.diagnostic, result.cause)
          }
        ProductionBridgeTestRenderDriver(environment, bridge)
      } catch (error: Throwable) {
        environment.close()
        throw error
      }
    }
  }
}

private abstract class DesktopTestGpuEnvironment : AutoCloseable {
  abstract val gpuHost: ComposeMapHost

  private var destination: Surface? = null
  private var destinationWidth = 0
  private var destinationHeight = 0

  abstract fun <T> withContext(action: (ComposeGpuContext) -> T): T

  fun present(bridge: MlnFfiMapHost, target: MlnFfiRenderTarget): Boolean = withContext { context ->
    val width = target.extent.physicalWidth
    val height = target.extent.physicalHeight
    val surface = destination(context, width, height)
    surface.canvas.clear(0x00000000)
    var drew = false
    PictureRecorder().use { recorder ->
      val recording = recorder.beginRecording(0f, 0f, width.toFloat(), height.toFloat())
      CanvasDrawScope().draw(
        Density(target.extent.scaleFactor.toFloat()),
        LayoutDirection.Ltr,
        recording.asComposeCanvas(),
        Size(width.toFloat(), height.toFloat()),
      ) {
        drew = bridge.draw(this, target)
      }
      recorder.finishRecordingAsPicture().use(surface.canvas::drawPicture)
    }
    if (drew) surface.flushAndSubmit()
    drew
  }

  fun readPixel(x: Int, y: Int): RgbaPixel = withContext {
    val surface = checkNotNull(destination) { "No production bridge frame has been presented" }
    require(x in 0 until destinationWidth && y in 0 until destinationHeight) {
      "Pixel ($x, $y) is outside ${destinationWidth}x$destinationHeight"
    }
    Bitmap().use { bitmap ->
      check(bitmap.allocN32Pixels(destinationWidth, destinationHeight)) {
        "Could not allocate bridge-test readback bitmap"
      }
      check(surface.readPixels(bitmap, 0, 0)) { "Skia could not read the presented map" }
      val color = bitmap.getColor(x, y)
      RgbaPixel(
        red = color ushr 16 and 0xff,
        green = color ushr 8 and 0xff,
        blue = color and 0xff,
        alpha = color ushr 24 and 0xff,
      )
    }
  }

  protected fun closeDestination() {
    if (destination == null) return
    withContext {
      destination?.close()
      destination = null
      destinationWidth = 0
      destinationHeight = 0
    }
  }

  private fun destination(context: ComposeGpuContext, width: Int, height: Int): Surface {
    if (destination != null && destinationWidth == width && destinationHeight == height) {
      return checkNotNull(destination)
    }
    destination?.close()
    destination =
      Surface.makeRenderTarget(
        context.skiaContext,
        false,
        ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
      )
    destinationWidth = width
    destinationHeight = height
    return checkNotNull(destination)
  }

  companion object {
    fun create(): DesktopTestGpuEnvironment =
      when {
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ->
          MetalTestGpuEnvironment.create()
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
          Direct3D12TestGpuEnvironment.create()
        System.getProperty("os.name").startsWith("Linux", ignoreCase = true) ->
          OpenGlTestGpuEnvironment.create()
        else ->
          error(
            "No production Desktop bridge test environment for ${System.getProperty("os.name")}"
          )
      }
  }
}

private class MetalTestGpuEnvironment
private constructor(
  private val gpuThread: MapRendererThread,
  private var framework: Long,
  private val device: Long,
  private val queue: Long,
  private val context: DirectContext,
) : DesktopTestGpuEnvironment() {
  private val composeContext = MetalComposeGpuContext(context, NativeHandle(device))

  override val gpuHost =
    object : ComposeMapHost {
      override val description = "the test Metal context"
      override val backend = ComposeRenderBackend.METAL

      override fun gpuContext(): ComposeGpuContext = composeContext

      override fun runOnGpuThread(action: Runnable) {
        gpuThread.run { ObjectiveC.runInAutoreleasePool { action.run() } }
      }
    }

  override fun <T> withContext(action: (ComposeGpuContext) -> T): T = gpuThread.run {
    ObjectiveC.runInAutoreleasePool { action(composeContext) }
  }

  override fun close() {
    try {
      closeDestination()
      gpuThread.run {
        ObjectiveC.runInAutoreleasePool {
          context.close()
          ObjectiveC.release(queue)
          ObjectiveC.release(device)
          if (framework != NULL) {
            dlclose(framework)
            framework = NULL
          }
        }
      }
    } finally {
      gpuThread.close()
    }
  }

  companion object {
    fun create(): MetalTestGpuEnvironment {
      val gpuThread = MapRendererThread("maplibre-metal-test-consumer")
      return try {
        gpuThread.run {
          ObjectiveC.runInAutoreleasePool {
            val framework =
              dlopen("/System/Library/Frameworks/Metal.framework/Metal", RTLD_NOW or RTLD_LOCAL)
            check(framework != NULL) { "Could not load Metal.framework" }
            try {
              val factory = dlsym(framework, "MTLCreateSystemDefaultDevice")
              check(factory != NULL) { "MTLCreateSystemDefaultDevice was not found" }
              val borrowedDevice = JNI.invokeP(factory)
              check(borrowedDevice != NULL) { "macOS has no system Metal device" }
              val device = ObjectiveC.sendPointer(borrowedDevice, "retain")
              val queue = ObjectiveC.sendPointer(device, "newCommandQueue")
              check(queue != NULL) { "The system Metal device could not create a command queue" }
              try {
                MetalTestGpuEnvironment(
                  gpuThread = gpuThread,
                  framework = framework,
                  device = device,
                  queue = queue,
                  context = DirectContext.makeMetal(device, queue),
                )
              } catch (error: Throwable) {
                ObjectiveC.release(queue)
                ObjectiveC.release(device)
                throw error
              }
            } catch (error: Throwable) {
              dlclose(framework)
              throw error
            }
          }
        }
      } catch (error: Throwable) {
        gpuThread.close()
        throw error
      }
    }
  }
}

private class OpenGlTestGpuEnvironment
private constructor(private val gpuThread: MapRendererThread, private val egl: EglTestContext) :
  DesktopTestGpuEnvironment() {
  private val composeContext =
    OpenGlComposeGpuContext(egl.directContext) { action -> egl.withCurrent { action.run() } }

  override val gpuHost =
    object : ComposeMapHost {
      override val description = "the test EGL OpenGL context"
      override val backend = ComposeRenderBackend.OPENGL

      override fun gpuContext(): ComposeGpuContext = composeContext

      override fun runOnGpuThread(action: Runnable) {
        gpuThread.run { egl.withCurrent { action.run() } }
      }
    }

  override fun <T> withContext(action: (ComposeGpuContext) -> T): T = gpuHost.withOpenGlContext {
    action(it)
  }

  override fun close() {
    try {
      closeDestination()
      gpuThread.run { egl.close() }
    } finally {
      gpuThread.close()
    }
  }

  companion object {
    fun create(): OpenGlTestGpuEnvironment {
      val gpuThread = MapRendererThread("maplibre-opengl-test-consumer")
      return try {
        OpenGlTestGpuEnvironment(gpuThread, gpuThread.run(EglTestContext::create))
      } catch (error: Throwable) {
        gpuThread.close()
        throw error
      }
    }
  }
}

@OptIn(ExperimentalComposeUiApi::class)
private class Direct3D12TestGpuEnvironment private constructor(private val window: ComposeWindow) :
  DesktopTestGpuEnvironment() {
  override val gpuHost: ComposeMapHost = AwtComposeMapHost(window)

  override fun <T> withContext(action: (ComposeGpuContext) -> T): T = gpuHost.onGpuThread {
    action(gpuHost.requireContext<Direct3D12ComposeGpuContext>())
  }

  override fun close() {
    closeDestination()
    scheduleDisposal(this)
  }

  companion object {
    /**
     * Skiko tears a window's Direct3D device down asynchronously after [ComposeWindow.dispose], so
     * replacing the window between test methods can race the next device's startup and crash the
     * test VM in the native graphics driver.
     */
    private const val DISPOSAL_DELAY_MILLIS = 1_000L
    private val sharedLock = Any()
    private var shared: Direct3D12TestGpuEnvironment? = null
    private var disposalGeneration = 0L

    fun create(): Direct3D12TestGpuEnvironment =
      synchronized(sharedLock) {
        disposalGeneration += 1
        shared ?: createShared().also { shared = it }
      }

    /** Disposes the last window after a reuse window, so AWT does not keep the worker alive. */
    private fun scheduleDisposal(environment: Direct3D12TestGpuEnvironment) {
      val scheduledGeneration = synchronized(sharedLock) { ++disposalGeneration }
      Thread(
          {
            Thread.sleep(DISPOSAL_DELAY_MILLIS)
            val shouldDispose =
              synchronized(sharedLock) {
                if (shared === environment && disposalGeneration == scheduledGeneration) {
                  shared = null
                  true
                } else {
                  false
                }
              }
            if (shouldDispose) {
              runCatching { EventQueue.invokeAndWait { environment.window.dispose() } }
            }
          },
          "maplibre-direct3d-test-disposal",
        )
        .apply { isDaemon = true }
        .start()
    }

    private fun createShared(): Direct3D12TestGpuEnvironment {
      lateinit var window: ComposeWindow
      EventQueue.invokeAndWait {
        window = ComposeWindow()
        window.isUndecorated = true
        window.setSize(WINDOW_WIDTH, WINDOW_HEIGHT)
        window.setLocation(-WINDOW_WIDTH * 2, -WINDOW_HEIGHT * 2)
        window.setContent {}
        window.isVisible = true
        window.renderImmediately()
      }
      val environment = Direct3D12TestGpuEnvironment(window)
      try {
        val deadline = TimeSource.Monotonic.markNow() + CONTEXT_TIMEOUT
        while (environment.gpuHost.currentContext() == null) {
          check(deadline.hasNotPassedNow()) { "Timed out waiting for Skiko's D3D12 context" }
          EventQueue.invokeAndWait { window.renderImmediately() }
          Thread.sleep(10)
        }
        return environment
      } catch (error: Throwable) {
        EventQueue.invokeAndWait { window.dispose() }
        throw error
      }
    }

    private const val WINDOW_WIDTH = 512
    private const val WINDOW_HEIGHT = 512
    private val CONTEXT_TIMEOUT = 30.seconds
  }
}

private class EglTestContext private constructor() : AutoCloseable {
  private val ownerThread = Thread.currentThread()
  private var display = EGL_NO_DISPLAY
  private var surface = EGL_NO_SURFACE
  private var context = EGL_NO_CONTEXT
  private lateinit var capabilities: org.lwjgl.opengl.GLCapabilities
  private lateinit var procAddressCallback: GlProcAddressCallback
  private lateinit var glInterface: GLAssembledInterface

  lateinit var directContext: DirectContext
    private set

  init {
    createEglContext()
    withCurrent {
      capabilities = GL.createCapabilities()
      procAddressCallback =
        object : GlProcAddressCallback() {
          override fun invoke(context: Long, name: Long): Long = neglGetProcAddress(name)
        }
      glInterface = GLAssembledInterface.createFromNativePointers(0, procAddressCallback.address())
      directContext = DirectContext.makeGLWithInterface(glInterface)
    }
  }

  fun <T> withCurrent(action: () -> T): T {
    check(Thread.currentThread() === ownerThread) { "EGL test context used from the wrong thread" }
    checkEgl(eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent")
    if (::capabilities.isInitialized) GL.setCapabilities(capabilities)
    return action()
  }

  private fun createEglContext() {
    if (runCatching { EGL.getCapabilities() }.isFailure) EGL.create()
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
    check(display != EGL_NO_DISPLAY) { eglFailure("eglGetDisplay") }

    MemoryStack.stackPush().use { stack ->
      val major = stack.mallocInt(1)
      val minor = stack.mallocInt(1)
      checkEgl(eglInitialize(display, major, minor), "eglInitialize")
      EGL.createDisplayCapabilities(display, major[0], minor[0])
      checkEgl(eglBindAPI(EGL_OPENGL_API), "eglBindAPI")

      val configs = stack.mallocPointer(1)
      val configCount = stack.mallocInt(1)
      val attributes =
        stack.ints(
          EGL_SURFACE_TYPE,
          EGL_PBUFFER_BIT,
          EGL_RENDERABLE_TYPE,
          EGL_OPENGL_BIT,
          EGL_RED_SIZE,
          8,
          EGL_GREEN_SIZE,
          8,
          EGL_BLUE_SIZE,
          8,
          EGL_ALPHA_SIZE,
          8,
          EGL_NONE,
        )
      checkEgl(eglChooseConfig(display, attributes, configs, configCount), "eglChooseConfig")
      check(configCount[0] > 0) { "EGL returned no pbuffer OpenGL config" }
      val config = configs[0]
      surface =
        eglCreatePbufferSurface(
          display,
          config,
          stack.ints(EGL_WIDTH, PBUFFER_SIZE, EGL_HEIGHT, PBUFFER_SIZE, EGL_NONE),
        )
      check(surface != EGL_NO_SURFACE) { eglFailure("eglCreatePbufferSurface") }
      context = eglCreateContext(display, config, EGL_NO_CONTEXT, stack.ints(EGL_NONE))
      check(context != EGL_NO_CONTEXT) { eglFailure("eglCreateContext") }
    }
  }

  override fun close() {
    if (display == EGL_NO_DISPLAY) return
    runCatching {
      withCurrent {
        if (::directContext.isInitialized) directContext.close()
        if (::glInterface.isInitialized) glInterface.close()
        if (::procAddressCallback.isInitialized) procAddressCallback.free()
      }
    }
    GL.setCapabilities(null)
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
    if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context)
    if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface)
    eglTerminate(display)
    context = EGL_NO_CONTEXT
    surface = EGL_NO_SURFACE
    display = EGL_NO_DISPLAY
  }

  private fun checkEgl(success: Boolean, operation: String) {
    check(success) { eglFailure(operation) }
  }

  private fun eglFailure(operation: String): String =
    "$operation failed with EGL error 0x${eglGetError().toString(16)}"

  companion object {
    private const val PBUFFER_SIZE = 1024

    fun create(): EglTestContext = EglTestContext()
  }
}

@java.lang.FunctionalInterface
private fun interface GlProcAddressCallbackI : CallbackI {
  fun invoke(context: Long, name: Long): Long

  override fun getDescriptor(): Callback.Descriptor = DESCRIPTOR

  override fun callback(ret: Long, args: Long) {
    val context = memGetAddress(args)
    val name = memGetAddress(memGetAddress(args + POINTER_SIZE))
    memPutAddress(ret, invoke(context, name))
  }

  companion object {
    val DESCRIPTOR =
      Callback.Descriptor(
        MethodHandles.lookup(),
        apiCreateCIF(ffi_type_pointer, ffi_type_pointer, ffi_type_pointer),
      )
  }
}

private abstract class GlProcAddressCallback :
  Callback(GlProcAddressCallbackI.DESCRIPTOR), GlProcAddressCallbackI {
  override fun address(): Long = super<Callback>.address()

  override fun getDescriptor(): Callback.Descriptor = super<GlProcAddressCallbackI>.getDescriptor()

  override fun callback(ret: Long, args: Long) {
    super<GlProcAddressCallbackI>.callback(ret, args)
  }

  abstract override fun invoke(context: Long, name: Long): Long
}
