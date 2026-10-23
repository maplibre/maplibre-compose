package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.lang.FunctionalInterface
import java.lang.invoke.MethodHandles
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
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
import org.lwjgl.egl.EGL10.neglGetProcAddress
import org.lwjgl.egl.EGL12.EGL_RENDERABLE_TYPE
import org.lwjgl.egl.EGL12.eglBindAPI
import org.lwjgl.egl.EGL14.EGL_DEFAULT_DISPLAY
import org.lwjgl.egl.EGL14.EGL_OPENGL_API
import org.lwjgl.egl.EGL14.EGL_OPENGL_BIT
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GLCapabilities
import org.lwjgl.system.APIUtil.apiCreateCIF
import org.lwjgl.system.Callback
import org.lwjgl.system.CallbackI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memGetAddress
import org.lwjgl.system.MemoryUtil.memPutAddress
import org.lwjgl.system.Pointer.POINTER_SIZE
import org.lwjgl.system.libffi.LibFFI.ffi_type_pointer
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.desktop.OpenGlPresentationHost
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.MlnFfiMapSession
import org.maplibre.compose.map.createNativeMapRuntime
import org.maplibre.compose.map.nativeOwner
import org.maplibre.compose.map.tryLoadRuntimeBackends
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
import org.maplibre.compose.mlnffi.MlnFfiMapRenderer
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.MlnFfiSurfaceController
import org.maplibre.compose.mlnffi.TestMapMain
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.runMapTest

class LinuxOpenGlInteropTest {

  @Test
  fun `losing the consumer context during surface loss or release closes the renderer`() =
    onLinux("the OpenGL consumer bridge exists only on Linux") {
      runMapTest {
        for (releasePresentation in listOf(false, true)) {
          EglTestContext.create().use { egl ->
            val presentationHost = EglPresentationHost(egl)
            val host = LinuxOpenGlMapHost(presentationHost.host, packagedProducer())
            try {
              InteropMap(host).use { map ->
                val frame = map.renderStyle(egl, FirstStyle, FirstExtent)
                assertNear(
                  FirstPixel,
                  egl.withCurrent { egl.drawAndRead(host, frame) },
                  "frame before context loss",
                )
                presentationHost.contextAvailable = false
                if (releasePresentation) map.releasePresentation()
                // close loses the surface while the adapter can no longer make its context current.
              }
            } finally {
              host.close()
            }
            assertFalse(
              host.enqueueRenderer { error("The renderer is closed") },
              "The map renderer thread survived surface loss",
            )
          }
        }
      }
    }

  @Test
  fun `a consumer completion exception still closes the host and renderer thread`() =
    onLinux("the OpenGL consumer bridge exists only on Linux") {
      var completions = 0
      val presentationHost =
        OpenGlPresentationHost(
          "a failed context acquisition",
          {
            completions++
            error("consumer completion failed")
          },
          { it.run() },
        )
      val host = LinuxOpenGlMapHost(presentationHost, packagedProducer())
      val renderer =
        object : MlnFfiMapRenderer {
          override val backend = host.backends.producer

          override fun render(
            host: MlnFfiMapHostSession,
            frame: MlnFfiMapFrame,
            captureProjection: Boolean,
          ): MlnFfiFrameResult = error("This surface never renders")

          override fun onSurfaceLost(session: MlnFfiMapHostSession) =
            session.completeConsumerAccess()

          override fun close() = Unit
        }
      val controller = MlnFfiSurfaceController(renderer, MlnFfiMapHostResult.Created(host), null)
      controller.attach {}
      controller.close()
      assertTrue(completions >= 2, "Both surface loss and host close must try completion")
      assertFalse(
        host.enqueueRenderer { error("The renderer is closed") },
        "The renderer thread survived a completion failure",
      )
    }

  @Test
  fun `an inherited GL error does not poison the first memory import`() =
    onLinux("importing a Vulkan memory fd into OpenGL is a Linux-only path") {
      EglTestContext.create().use { egl ->
        val host = LinuxOpenGlMapHost(EglPresentationHost(egl).host, packagedProducer())
        try {
          egl.withCurrent {
            clearGlErrors()
            // OpenGL errors are sticky; leave one behind for the bridge to trip over.
            glEnable(Int.MIN_VALUE)
            val frame =
              assertIs<MlnFfiMapFrameAcquisition.Acquired>(host.acquireFrame(FirstExtent)).frame
            host.releaseFrame(frame)
          }
        } finally {
          host.close()
        }
      }
    }

  @Test
  fun `a resize can still present the last completed generation`() =
    onLinux("the Linux OpenGL bridge this resizes exists only on Linux") {
      runMapTest {
        EglTestContext.create().use { egl ->
          val host = LinuxOpenGlMapHost(EglPresentationHost(egl).host, packagedProducer())
          try {
            val first =
              InteropMap(host).use { map -> map.renderStyle(egl, FirstStyle, FirstExtent) }
            InteropMap(host).use { map ->
              val second = map.renderStyle(egl, SecondStyle, SecondExtent)

              val oldPixel = egl.withCurrent { egl.drawAndRead(host, first) }
              assertNear(FirstPixel, oldPixel, "retired generation after resize")

              val newPixel = egl.withCurrent { egl.drawAndRead(host, second) }
              assertNear(SecondPixel, newPixel, "current generation after resize")
            }
          } finally {
            host.close()
          }
        }
      }
    }

  @Test
  fun `a replacement Compose context gets a new shared target`() =
    onLinux("the Linux OpenGL bridge this replaces exists only on Linux") {
      runMapTest {
        EglTestContext.create().use { firstEgl ->
          EglTestContext.create().use { secondEgl ->
            val presentationHost = EglPresentationHost(firstEgl)
            val host = LinuxOpenGlMapHost(presentationHost.host, packagedProducer())
            try {
              InteropMap(host).use { map ->
                val first = map.renderStyle(firstEgl, FirstStyle, FirstExtent)
                assertNear(
                  FirstPixel,
                  firstEgl.withCurrent { firstEgl.drawAndRead(host, first) },
                  "first context before replacement",
                )

                presentationHost.replaceContext(secondEgl)
                val second = map.awaitFrame(secondEgl, FirstExtent)
                assertTrue(
                  second.generation != first.generation,
                  "replacement context must allocate a new shared target, " +
                    "got generation ${second.generation} after ${first.generation}",
                )
                assertNear(
                  FirstPixel,
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
    }

  @Test
  fun `reusing the shared target presents the new pixels`() =
    onLinux("the Linux OpenGL bridge this reuses exists only on Linux") {
      runMapTest {
        EglTestContext.create().use { egl ->
          val host = LinuxOpenGlMapHost(EglPresentationHost(egl).host, packagedProducer())
          try {
            InteropMap(host).use { map ->
              val first = map.renderStyle(egl, FirstStyle, FirstExtent)
              assertNear(
                FirstPixel,
                egl.withCurrent { egl.drawAndRead(host, first) },
                "live first frame",
              )
              val second = map.renderStyle(egl, SecondStyle, FirstExtent)
              assertNear(
                SecondPixel,
                egl.withCurrent { egl.drawAndRead(host, second) },
                "live second frame after reuse",
              )
            }
          } finally {
            host.close()
          }
        }
      }
    }

  private inline fun onLinux(reason: String, block: () -> Unit) {
    assumeTrue(reason, System.getProperty("os.name").orEmpty().lowercase().contains("linux"))
    block()
  }

  private fun packagedProducer(): MapRenderBackend =
    when (val backend = tryLoadRuntimeBackends().getOrThrow().single()) {
      MapRenderBackend.OpenGl -> MapRenderBackend.OpenGl
      MapRenderBackend.Vulkan -> MapRenderBackend.Vulkan
      else -> error("No Linux OpenGL bridge for $backend")
    }

  private fun assertNear(expected: RgbaPixel, actual: RgbaPixel, label: String) {
    assertTrue(
      near(expected, actual),
      "$label: expected $expected within $ChannelTolerance per channel, got $actual",
    )
  }

  private class EglPresentationHost(egl: EglTestContext) {
    private val ownerThread = Thread.currentThread()
    private var context = egl.asComposeContext()
    var contextAvailable = true

    val host = OpenGlPresentationHost("the test EGL OpenGL context", { context }, ::runOnGpuThread)

    fun replaceContext(egl: EglTestContext) {
      context = egl.asComposeContext()
    }

    private fun runOnGpuThread(action: Runnable) {
      check(Thread.currentThread() === ownerThread) {
        "EGL test context used from the wrong thread"
      }
      action.run()
    }

    private fun EglTestContext.asComposeContext() =
      OpenGlComposeGpuContext(directContext) { action ->
        if (contextAvailable) withCurrent { action.run() }
      }
  }

  /**
   * A map on [TestMapMain] whose frames the test renders into [host] on that thread, with the
   * test's EGL context current.
   */
  private class InteropMap(private val host: LinuxOpenGlMapHost) {
    private val cacheDirectory = Files.createTempDirectory("maplibre-egl-interop-test")

    private val styleLoads = MutableStateFlow(0)
    private val failure = MutableStateFlow<String?>(null)
    private val frameRequests = MutableStateFlow(0L)

    private val callbacks =
      object : MapAdapter.Callbacks {
        override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
          if (style != null) styleLoads.update { it + 1 }
        }

        override fun onStyleReady(map: MapAdapter) {}

        override fun onStyleFailed(map: MapAdapter, reason: String?) {
          failure.value = reason ?: "unknown map load failure"
        }

        override fun onStyleSourcesChanged(map: MapAdapter, sourceId: String) {}

        override fun onEvent(map: MapAdapter, event: MapEvent) {}

        override fun resolveMissingImage(map: MapAdapter, imageId: String): Deferred<Unit>? = null

        override fun onGestureActive(map: MapAdapter, active: Boolean) {}

        override fun onViewportChanged(map: MapAdapter) {}
      }

    private val runtime =
      createNativeMapRuntime(
        MlnFfiRuntimeOptions(
          cacheFile = Path(cacheDirectory.resolve("cache.db").toString()),
          mainDispatcher = TestMapMain.dispatcher,
        ),
      )
    private val state = runtime.createMapState(BaseStyle.Demo)
    private val renderer =
      MlnFfiMapSession(
        lifecycleAuthority = state.lifecycle,
        callbacks = callbacks,
        logger = null,
        renderBackend = host.backends.producer,
        layoutDirection = LayoutDirection.Ltr,
        owner = runtime.nativeOwner,
      )

    private val hostSession =
      object : MlnFfiMapHostSession {
        override val isClosed = false
        override val backends = host.backends

        override fun requestFrame() {
          frameRequests.update { it + 1 }
        }

        override fun completeConsumerAccess() = host.completeConsumerAccess()

        override fun <T> withRendererAccess(action: () -> T): T = host.withRendererAccess(action)

        override fun enqueueRenderer(action: () -> Unit): Boolean = host.enqueueRenderer(action)
      }

    init {
      state.reservePresentation()
      renderer.beginPresentationAttachment()
      renderer.onSurfaceAvailable(hostSession)
    }

    /** Applies [style] and returns the first frame rendered at [extent] after it loads. */
    suspend fun renderStyle(
      egl: EglTestContext,
      style: BaseStyle,
      extent: MapExtent,
    ): MlnFfiRenderTarget {
      val expectedStyleLoads = styleLoads.value + 1
      renderer.setBaseStyle(style)
      // A frame rendered before the style applies would still show the previous one.
      withTimeoutOrNull(TestTimeout) {
        combine(styleLoads, failure) { loads, failed ->
            failed?.let { error(it) }
            loads >= expectedStyleLoads
          }
          .first { it }
      }
        ?: error(
          "Timed out loading style $style; style loads: ${styleLoads.value}/$expectedStyleLoads",
        )
      return awaitFrame(egl, extent)
    }

    /** Renders at [extent] with [egl] current, and again whenever the map asks, until one draws. */
    suspend fun awaitFrame(egl: EglTestContext, extent: MapExtent): MlnFfiRenderTarget {
      var rendered: MlnFfiRenderTarget? = null
      var lastResult: MlnFfiFrameResult? = null
      withTimeoutOrNull(TestTimeout) {
        while (true) {
          failure.value?.let { error(it) }
          val requested = frameRequests.value
          val visit = egl.withCurrent { renderFrame(extent) }
          lastResult = visit.result
          rendered = visit.target
          if (rendered != null) break
          if (visit.result == MlnFfiFrameResult.RetryNextFrame) delay(FrameInterval)
          else frameRequests.first { it != requested }
        }
      }
      return rendered
        ?: error(
          "Timed out rendering at $extent; last result: $lastResult, failure: ${failure.value}",
        )
    }

    private fun renderFrame(extent: MapExtent): FrameVisit {
      val frame = assertIs<MlnFfiMapFrameAcquisition.Acquired>(host.acquireFrame(extent)).frame
      try {
        val result = host.withProducerAccess(frame) { renderer.render(hostSession, frame) }
        if (result is MlnFfiFrameResult.Rendered) {
          host.completeProducerAccess(frame)
          return FrameVisit(result, frame.target)
        }
        return FrameVisit(result, null)
      } finally {
        host.releaseFrame(frame)
      }
    }

    suspend fun releasePresentation() = renderer.presentation.release()

    /** Runs [block] with this map, then loses its surface and closes its runtime. */
    suspend inline fun <T> use(block: (InteropMap) -> T): T =
      try {
        block(this)
      } finally {
        close()
      }

    suspend fun close() {
      renderer.onSurfaceLost(hostSession)
      runtime.close()
      runtime.awaitClosed()
      cacheDirectory.toFile().deleteRecursively()
    }

    private class FrameVisit(val result: MlnFfiFrameResult, val target: MlnFfiRenderTarget?)
  }

  private class EglTestContext private constructor() : AutoCloseable {
    private val ownerThread = Thread.currentThread()
    private var display = EGL_NO_DISPLAY
    private var surface = EGL_NO_SURFACE
    private var context = EGL_NO_CONTEXT
    private lateinit var capabilities: GLCapabilities
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
              ImageInfo(DrawWidth, DrawHeight, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
            ),
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

    fun drawAndRead(host: LinuxOpenGlMapHost, target: MlnFfiRenderTarget): RgbaPixel {
      destination.canvas.clear(0xff00ff00.toInt())
      var drew = false
      CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        destination.canvas.asComposeCanvas(),
        Size(DrawWidth.toFloat(), DrawHeight.toFloat()),
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
        assertTrue(bitmap.allocN32Pixels(DrawWidth, DrawHeight), "Could not allocate readback")
        assertTrue(destination.readPixels(bitmap, 0, 0), "Skia could not read the presented map")
        val color = bitmap.getColor(DrawWidth / 2, DrawHeight / 2)
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
            stack.ints(EGL_WIDTH, DrawWidth, EGL_HEIGHT, DrawHeight, EGL_NONE),
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
      // The display is shared with other fixtures and map producers; only our resources are owned.
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

  @FunctionalInterface
  private fun interface GlProcAddressCallbackI : CallbackI {
    fun invoke(context: Long, name: Long): Long

    override fun getDescriptor(): Callback.Descriptor = Descriptor

    override fun callback(ret: Long, args: Long) {
      val context = memGetAddress(args)
      val name = memGetAddress(memGetAddress(args + POINTER_SIZE))
      memPutAddress(ret, invoke(context, name))
    }

    companion object {
      val Descriptor =
        Callback.Descriptor(
          GlProcAddressCallbackI::class.java,
          MethodHandles.lookup(),
          apiCreateCIF(ffi_type_pointer, ffi_type_pointer, ffi_type_pointer),
        )
    }
  }

  private abstract class GlProcAddressCallback :
    Callback(GlProcAddressCallbackI.Descriptor), GlProcAddressCallbackI {
    override fun address(): Long = super<Callback>.address()

    override fun getDescriptor(): Callback.Descriptor =
      super<GlProcAddressCallbackI>.getDescriptor()

    override fun callback(ret: Long, args: Long) {
      super<GlProcAddressCallbackI>.callback(ret, args)
    }

    abstract override fun invoke(context: Long, name: Long): Long
  }

  private companion object {
    const val DrawWidth = 320
    const val DrawHeight = 240
    const val ChannelTolerance = 2

    fun near(expected: RgbaPixel, actual: RgbaPixel): Boolean =
      abs(expected.red - actual.red) <= ChannelTolerance &&
        abs(expected.green - actual.green) <= ChannelTolerance &&
        abs(expected.blue - actual.blue) <= ChannelTolerance &&
        abs(expected.alpha - actual.alpha) <= ChannelTolerance

    val TestTimeout = 30.seconds

    /** About one display frame at 120 Hz. */
    val FrameInterval = 8.milliseconds

    val FirstExtent = MapExtent.fromLogical(256, 192, 1.0)
    val SecondExtent = MapExtent.fromLogical(320, 240, 1.0)

    val FirstPixel = RgbaPixel(red = 0x33, green = 0x66, blue = 0x99, alpha = 0xff)
    val SecondPixel = RgbaPixel(red = 0x99, green = 0x33, blue = 0x66, alpha = 0xff)

    val FirstStyle = solidStyle("#336699")
    val SecondStyle = solidStyle("#993366")

    fun solidStyle(color: String) =
      BaseStyle.Json(
        """
        {"version":8,"transition":{"duration":0,"delay":0},"sources":{},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"$color"}}
        ]}
        """,
      )
  }
}
