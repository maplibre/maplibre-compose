package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.lang.invoke.MethodHandles
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Deferred
import kotlinx.io.files.Path
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.makeGLWithInterface
import org.junit.Assume.assumeTrue
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
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.system.APIUtil.apiCreateCIF
import org.lwjgl.system.Callback
import org.lwjgl.system.CallbackI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memGetAddress
import org.lwjgl.system.MemoryUtil.memPutAddress
import org.lwjgl.system.Pointer.POINTER_SIZE
import org.lwjgl.system.libffi.LibFFI.ffi_type_pointer
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeMapPresentationHost
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.MlnFfiMapSession
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

class LinuxVulkanOpenGlInteropTest {

  @Test
  fun `an inherited GL error does not poison the first memory import`() =
    onLinux("importing a Vulkan memory fd into OpenGL is a Linux-only path") {
      EglTestContext.create().use { egl ->
        val host = VulkanOpenGlMapHost(EglPresentationHost(egl))
        try {
          egl.withCurrent {
            clearGlErrors()
            // OpenGL errors are sticky; leave one behind for the bridge to trip over.
            glEnable(Int.MIN_VALUE)
            val frame =
              assertIs<MlnFfiMapFrameAcquisition.Acquired>(host.acquireFrame(1, FIRST_EXTENT, null))
                .frame
            host.releaseFrame(frame)
          }
        } finally {
          host.close()
        }
      }
    }

  @Test
  fun `a resize can still present the last completed generation`() =
    onLinux("the Vulkan to OpenGL bridge this resizes exists only on Linux") {
      EglTestContext.create().use { egl ->
        val host = VulkanOpenGlMapHost(EglPresentationHost(egl))
        try {
          val first =
            InteropMap(host).use { map ->
              egl.withCurrent { map.renderStyle(FIRST_STYLE, FIRST_EXTENT) }
            }
          InteropMap(host).use { map ->
            val second = egl.withCurrent { map.renderStyle(SECOND_STYLE, SECOND_EXTENT) }

            val oldPixel = egl.withCurrent { egl.drawAndRead(host, first) }
            assertNear(FIRST_PIXEL, oldPixel, "retired generation after resize")

            val newPixel = egl.withCurrent { egl.drawAndRead(host, second) }
            assertNear(SECOND_PIXEL, newPixel, "current generation after resize")
          }
        } finally {
          host.close()
        }
      }
    }

  @Test
  fun `a replacement Compose context gets a new shared target`() =
    onLinux("the Vulkan to OpenGL bridge this replaces exists only on Linux") {
      EglTestContext.create().use { firstEgl ->
        EglTestContext.create().use { secondEgl ->
          val presentationHost = EglPresentationHost(firstEgl)
          val host = VulkanOpenGlMapHost(presentationHost)
          try {
            InteropMap(host).use { map ->
              val first = firstEgl.withCurrent { map.renderStyle(FIRST_STYLE, FIRST_EXTENT) }
              assertNear(
                FIRST_PIXEL,
                firstEgl.withCurrent { firstEgl.drawAndRead(host, first) },
                "first context before replacement",
              )

              presentationHost.replaceContext(secondEgl)
              val second = secondEgl.withCurrent { map.pumpUntilRendered(FIRST_EXTENT) }
              assertTrue(
                second.generation != first.generation,
                "replacement context must allocate a new shared target, " +
                  "got generation ${second.generation} after ${first.generation}",
              )
              assertNear(
                FIRST_PIXEL,
                secondEgl.withCurrent { secondEgl.drawAndRead(host, second) },
                "replacement context after a new shared target",
              )
            }
          } finally {
            host.close()
          }
        }
      }
    }

  @Test
  fun `reusing the shared target presents the new pixels`() =
    onLinux("the Vulkan to OpenGL bridge this reuses exists only on Linux") {
      EglTestContext.create().use { egl ->
        val host = VulkanOpenGlMapHost(EglPresentationHost(egl))
        try {
          InteropMap(host).use { map ->
            egl.withCurrent {
              val first = map.renderStyle(FIRST_STYLE, FIRST_EXTENT)
              assertNear(FIRST_PIXEL, egl.drawAndRead(host, first), "live first frame")
              val second = map.renderStyle(SECOND_STYLE, FIRST_EXTENT)
              assertNear(
                SECOND_PIXEL,
                egl.drawAndRead(host, second),
                "live second frame after reuse",
              )
            }
          }
        } finally {
          host.close()
        }
      }
    }

  private inline fun onLinux(reason: String, block: () -> Unit) {
    assumeTrue(reason, System.getProperty("os.name").orEmpty().lowercase().contains("linux"))
    assumeTrue(
      "the Vulkan to OpenGL bridge needs the Vulkan runtime packaged",
      packagedRuntime() == RenderBackend.VULKAN,
    )
    block()
  }

  private fun packagedRuntime(): RenderBackend? = runCatching {
    Maplibre.loadNativeLibrary()
    Maplibre.supportedRenderBackends().singleOrNull()
  }
    .getOrNull()

  private fun assertNear(expected: RgbaPixel, actual: RgbaPixel, label: String) {
    assertTrue(
      near(expected, actual),
      "$label: expected $expected within $CHANNEL_TOLERANCE per channel, got $actual",
    )
  }

  private class EglPresentationHost(egl: EglTestContext) : ComposeMapPresentationHost {
    private val ownerThread = Thread.currentThread()
    private var context = egl.asComposeContext()

    override val description: String = "the test EGL OpenGL context"
    override val backend: ComposeRenderBackend = ComposeRenderBackend.OPENGL

    override fun gpuContext(): ComposeGpuContext = context

    fun replaceContext(egl: EglTestContext) {
      context = egl.asComposeContext()
    }

    override fun runOnGpuThread(action: Runnable) {
      check(Thread.currentThread() === ownerThread) {
        "EGL test context used from the wrong thread"
      }
      action.run()
    }

    private fun EglTestContext.asComposeContext() =
      OpenGlComposeGpuContext(directContext) { action -> withCurrent { action.run() } }
  }

  private class InteropMap(private val host: VulkanOpenGlMapHost) : AutoCloseable {
    private val cacheDirectory = Files.createTempDirectory("maplibre-egl-interop-test")
    private var nextFrameId = 1L

    @Volatile private var styleLoads = 0
    @Volatile private var failure: String? = null

    private val callbacks =
      object : MapAdapter.Callbacks {
        override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
          if (style != null) styleLoads++
        }

        override fun onStyleReady(map: MapAdapter) {}

        override fun onStyleFailed(map: MapAdapter, reason: String?) {
          failure = reason ?: "unknown map load failure"
        }

        override fun onStyleSourcesChanged(map: MapAdapter, sourceId: String?) {}

        override fun onEvent(map: MapAdapter, event: MapEvent) {}

        override fun resolveMissingImage(map: MapAdapter, imageId: String): Deferred<Unit>? = null

        override fun onGestureActive(map: MapAdapter, active: Boolean) {}

        override fun onViewportChanged(map: MapAdapter) {}
      }

    private val runtime = mapRuntimeForTest()
    private val state = runtime.createMapState(BaseStyle.Demo)
    private val renderer =
      MlnFfiMapSession(
        lifecycleAuthority = state.lifecycle,
        callbacks = callbacks,
        logger = null,
        renderBackend = host.backends.producer,
        layoutDirection = LayoutDirection.Ltr,
        cacheFile = Path(cacheDirectory.resolve("cache.db").toString()),
      )

    private val hostSession =
      object : MlnFfiMapHostSession {
        override val backends = host.backends

        override fun requestFrame() {}

        override fun <T> withRendererAccess(action: () -> T): T = host.withRendererAccess(action)
      }

    init {
      renderer.start()
      renderer.onSurfaceAvailable(hostSession)
    }

    fun renderStyle(style: BaseStyle, extent: MapExtent): MlnFfiRenderTarget {
      val expectedStyleLoads = styleLoads + 1
      renderer.setBaseStyle(style)
      val deadline = TimeSource.Monotonic.markNow() + TEST_TIMEOUT
      var rendered: MlnFfiRenderTarget? = null
      var renderedFrames = 0
      var lastResult: MlnFfiFrameResult? = null
      // Style application and rendering run on different threads. A frame that started
      // before
      // the new style was observed is the previous style, even if the callback arrives before
      // this loop looks again. Sample the load count first, then keep only a later frame.
      while (styleLoads < expectedStyleLoads || rendered == null) {
        check(deadline.hasNotPassedNow()) {
          "Timed out rendering style $style at $extent; " +
            "style loads: $styleLoads/$expectedStyleLoads, rendered frames: $renderedFrames, " +
            "last result: $lastResult, failure: $failure"
        }
        failure?.let { error(it) }
        val loadsBeforePump = styleLoads
        val pumped = pumpFrame(extent)
        lastResult = pumped.result
        if (loadsBeforePump >= expectedStyleLoads && pumped.rendered) {
          renderedFrames++
          rendered = pumped.target
        }
        Thread.sleep(POLL_INTERVAL_MILLIS)
      }
      return checkNotNull(rendered)
    }

    fun pumpUntilRendered(extent: MapExtent): MlnFfiRenderTarget {
      val deadline = TimeSource.Monotonic.markNow() + TEST_TIMEOUT
      var lastResult: MlnFfiFrameResult? = null
      while (true) {
        check(deadline.hasNotPassedNow()) {
          "Timed out rendering at $extent; last result: $lastResult, failure: $failure"
        }
        failure?.let { error(it) }
        val pumped = pumpFrame(extent)
        lastResult = pumped.result
        if (pumped.rendered) return checkNotNull(pumped.target)
        Thread.sleep(POLL_INTERVAL_MILLIS)
      }
    }

    private fun pumpFrame(extent: MapExtent): PumpedFrame {
      val frame =
        assertIs<MlnFfiMapFrameAcquisition.Acquired>(host.acquireFrame(nextFrameId++, extent, null))
          .frame
      try {
        val result = host.withProducerAccess(frame) { renderer.render(frame) }
        if (result == MlnFfiFrameResult.RENDERED) {
          host.completeProducerAccess(frame)
          return PumpedFrame(result, frame.target)
        }
        return PumpedFrame(result, null)
      } finally {
        host.releaseFrame(frame)
      }
    }

    override fun close() {
      state.close()
      runtime.close()
      cacheDirectory.toFile().deleteRecursively()
    }

    private class PumpedFrame(val result: MlnFfiFrameResult, val target: MlnFfiRenderTarget?) {
      val rendered: Boolean
        get() = result == MlnFfiFrameResult.RENDERED && target != null
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

    private lateinit var destination: Surface

    init {
      createEglContext()
      withCurrent {
        capabilities = GL.createCapabilities()
        procAddressCallback =
          object : GlProcAddressCallback() {
            override fun invoke(context: Long, name: Long): Long = neglGetProcAddress(name)
          }
        glInterface =
          GLAssembledInterface.createFromNativePointers(0, procAddressCallback.address())
        directContext = DirectContext.makeGLWithInterface(glInterface)
        destination =
          checkNotNull(
            Surface.makeRenderTarget(
              directContext,
              false,
              ImageInfo(DRAW_WIDTH, DRAW_HEIGHT, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
            )
          ) {
            "Skia could not create the EGL test render target"
          }
      }
    }

    fun <T> withCurrent(action: () -> T): T {
      check(Thread.currentThread() === ownerThread) {
        "EGL test context used from the wrong thread"
      }
      checkEgl(eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent")
      if (::capabilities.isInitialized) GL.setCapabilities(capabilities)
      return action()
    }

    fun drawAndRead(host: VulkanOpenGlMapHost, target: MlnFfiRenderTarget): RgbaPixel {
      destination.canvas.clear(0xff00ff00.toInt())
      var drew = false
      CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        destination.canvas.asComposeCanvas(),
        Size(DRAW_WIDTH.toFloat(), DRAW_HEIGHT.toFloat()),
      ) {
        drew =
          host.draw(
            this,
            target,
            MlnFfiMapDestination(0, 0, target.extent.physicalWidth, target.extent.physicalHeight),
          )
      }
      assertTrue(drew, "The OpenGL host did not draw generation ${target.generation}")
      return readDestination()
    }

    private fun readDestination(): RgbaPixel {
      destination.flushAndSubmit()

      Bitmap().use { bitmap ->
        assertTrue(bitmap.allocN32Pixels(DRAW_WIDTH, DRAW_HEIGHT), "Could not allocate readback")
        assertTrue(destination.readPixels(bitmap, 0, 0), "Skia could not read the presented map")
        val color = bitmap.getColor(DRAW_WIDTH / 2, DRAW_HEIGHT / 2)
        return RgbaPixel(
          red = color ushr 16 and 0xff,
          green = color ushr 8 and 0xff,
          blue = color and 0xff,
          alpha = color ushr 24 and 0xff,
        )
      }
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
        val configAttributes =
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
        checkEgl(
          eglChooseConfig(display, configAttributes, configs, configCount),
          "eglChooseConfig",
        )
        check(configCount[0] > 0) { "EGL returned no pbuffer OpenGL config" }
        val config = configs[0]

        surface =
          eglCreatePbufferSurface(
            display,
            config,
            stack.ints(EGL_WIDTH, DRAW_WIDTH, EGL_HEIGHT, DRAW_HEIGHT, EGL_NONE),
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
          if (::destination.isInitialized) destination.close()
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

    private fun eglFailure(operation: String): String {
      val error = eglGetError()
      return "$operation failed with EGL error 0x${error.toString(16)}"
    }

    companion object {
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
          GlProcAddressCallbackI::class.java,
          MethodHandles.lookup(),
          apiCreateCIF(ffi_type_pointer, ffi_type_pointer, ffi_type_pointer),
        )
    }
  }

  private abstract class GlProcAddressCallback :
    Callback(GlProcAddressCallbackI.DESCRIPTOR), GlProcAddressCallbackI {
    override fun address(): Long = super<Callback>.address()

    override fun getDescriptor(): Callback.Descriptor =
      super<GlProcAddressCallbackI>.getDescriptor()

    override fun callback(ret: Long, args: Long) {
      super<GlProcAddressCallbackI>.callback(ret, args)
    }

    abstract override fun invoke(context: Long, name: Long): Long
  }

  private companion object {
    const val DRAW_WIDTH = 320
    const val DRAW_HEIGHT = 240
    const val POLL_INTERVAL_MILLIS = 8L
    const val CHANNEL_TOLERANCE = 2

    fun near(expected: RgbaPixel, actual: RgbaPixel): Boolean =
      abs(expected.red - actual.red) <= CHANNEL_TOLERANCE &&
        abs(expected.green - actual.green) <= CHANNEL_TOLERANCE &&
        abs(expected.blue - actual.blue) <= CHANNEL_TOLERANCE &&
        abs(expected.alpha - actual.alpha) <= CHANNEL_TOLERANCE

    val TEST_TIMEOUT = 30.seconds

    val FIRST_EXTENT = MapExtent.fromLogical(256, 192, 1.0)
    val SECOND_EXTENT = MapExtent.fromLogical(320, 240, 1.0)

    val FIRST_PIXEL = RgbaPixel(red = 0x33, green = 0x66, blue = 0x99, alpha = 0xff)
    val SECOND_PIXEL = RgbaPixel(red = 0x99, green = 0x33, blue = 0x66, alpha = 0xff)

    val FIRST_STYLE = solidStyle("#336699")
    val SECOND_STYLE = solidStyle("#993366")

    fun solidStyle(color: String) =
      BaseStyle.Json(
        """
        {"version":8,"transition":{"duration":0,"delay":0},"sources":{},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"$color"}}
        ]}
        """
      )
  }
}
