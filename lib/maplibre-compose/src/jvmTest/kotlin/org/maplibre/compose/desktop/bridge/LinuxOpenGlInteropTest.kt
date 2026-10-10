package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.lang.FunctionalInterface
import java.lang.invoke.MethodHandles
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
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
import org.maplibre.compose.map.UnconfinedMain
import org.maplibre.compose.map.createNativeMapRuntime
import org.maplibre.compose.map.nativeOwner
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

class LinuxOpenGlInteropTest {

  @Test
  fun `asynchronous presentation stops requesting frames after the map settles`() =
    onLinux("asynchronous presentation is a Linux Vulkan experiment") {
      assumeTrue(packagedProducer() == MapRenderBackend.Vulkan)
      EglTestContext.create().use { egl ->
        val host =
          LinuxOpenGlMapHost(EglPresentationHost(egl).host, packagedProducer(), asyncFrames = true)
        try {
          InteropMap(host).use { map ->
            egl.withCurrent {
              host.setPresentedTarget(map.renderStyle(FirstStyle, FirstExtent))
              val deadline = TimeSource.Monotonic.markNow() + 5.seconds
              while (map.renderOnDemand(FirstExtent, 250) > 0) {
                check(deadline.hasNotPassedNow()) { "An idle map is still rendering" }
              }
              assertEquals(0, map.renderOnDemand(FirstExtent, 500))
            }
          }
        } finally {
          host.close()
        }
      }
    }

  @Test
  fun `asynchronous presentation freezes completed pixels without rotating the producer`() =
    onLinux("external-memory buffering is a Linux-only experiment") {
      assumeTrue(packagedProducer() == MapRenderBackend.Vulkan)
      EglTestContext.create().use { egl ->
        val host =
          LinuxOpenGlMapHost(EglPresentationHost(egl).host, packagedProducer(), asyncFrames = true)
        try {
          InteropMap(host).use { map ->
            egl.withCurrent {
              val first = map.renderStyle(FirstStyle, FirstExtent)
              host.setPresentedTarget(first)
              assertNear(FirstPixel, egl.drawAndRead(host, first), "first completed frame")
              val retainedPicture = egl.recordFrame(host, first)
              val second = map.renderStyle(SecondStyle, FirstExtent)
              assertEquals(first.generation, second.generation)
              assertNear(
                FirstPixel,
                egl.drawAndRead(host, first),
                "first frame while second is ready",
              )
              host.setPresentedTarget(second)
              assertNear(SecondPixel, egl.drawAndRead(host, second), "second completed frame")
              val third = map.renderStyle(solidStyle("#669933"), FirstExtent)
              assertEquals(
                (first as org.maplibre.compose.mlnffi.VulkanImageTarget).image,
                (third as org.maplibre.compose.mlnffi.VulkanImageTarget).image,
                "Native must keep the same producer allocation",
              )
              assertNear(
                SecondPixel,
                egl.drawAndRead(host, second),
                "second frame after first allocation is reused",
              )
              host.setPresentedTarget(third)
              val thirdPixel = RgbaPixel(0x66, 0x99, 0x33, 0xff)
              assertNear(thirdPixel, egl.drawAndRead(host, third), "third completed frame")
              retainedPicture.use {
                assertNear(
                  FirstPixel,
                  egl.replayAndRead(it),
                  "recorded first frame after its allocation was overwritten",
                )
              }
              val resized = map.renderStyle(SecondStyle, SecondExtent)
              assertNear(thirdPixel, egl.drawAndRead(host, third), "retained frame while resizing")
              host.setPresentedTarget(resized)
              assertNear(SecondPixel, egl.drawAndRead(host, resized), "resized completed frame")
            }
          }
        } finally {
          host.close()
        }
      }
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
      EglTestContext.create().use { egl ->
        val host = LinuxOpenGlMapHost(EglPresentationHost(egl).host, packagedProducer())
        try {
          val first =
            InteropMap(host).use { map ->
              egl.withCurrent { map.renderStyle(FirstStyle, FirstExtent) }
            }
          InteropMap(host).use { map ->
            val second = egl.withCurrent { map.renderStyle(SecondStyle, SecondExtent) }

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

  @Test
  fun `a replacement Compose context gets a new shared target`() =
    onLinux("the Linux OpenGL bridge this replaces exists only on Linux") {
      EglTestContext.create().use { firstEgl ->
        EglTestContext.create().use { secondEgl ->
          val presentationHost = EglPresentationHost(firstEgl)
          val host = LinuxOpenGlMapHost(presentationHost.host, packagedProducer())
          try {
            InteropMap(host).use { map ->
              val first = firstEgl.withCurrent { map.renderStyle(FirstStyle, FirstExtent) }
              assertNear(
                FirstPixel,
                firstEgl.withCurrent { firstEgl.drawAndRead(host, first) },
                "first context before replacement",
              )

              presentationHost.replaceContext(secondEgl)
              val second = secondEgl.withCurrent { map.pumpUntilRendered(FirstExtent) }
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

  @Test
  fun `reusing the shared target presents the new pixels`() =
    onLinux("the Linux OpenGL bridge this reuses exists only on Linux") {
      EglTestContext.create().use { egl ->
        val host = LinuxOpenGlMapHost(EglPresentationHost(egl).host, packagedProducer())
        try {
          InteropMap(host).use { map ->
            egl.withCurrent {
              val first = map.renderStyle(FirstStyle, FirstExtent)
              assertNear(FirstPixel, egl.drawAndRead(host, first), "live first frame")
              val second = map.renderStyle(SecondStyle, FirstExtent)
              assertNear(
                SecondPixel,
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
    block()
  }

  private fun packagedProducer(): MapRenderBackend =
    when (val backend = Maplibre.supportedRenderBackends().single()) {
      RenderBackend.OPENGL -> MapRenderBackend.OpenGl
      RenderBackend.VULKAN -> MapRenderBackend.Vulkan
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
        withCurrent { action.run() }
      }
  }

  private class InteropMap(private val host: LinuxOpenGlMapHost) : AutoCloseable {
    private val frameRequested = AtomicBoolean(true)
    private val cacheDirectory = Files.createTempDirectory("maplibre-egl-interop-test")

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

        override fun onStyleSourcesChanged(map: MapAdapter) {}

        override fun onEvent(map: MapAdapter, event: MapEvent) {}

        override fun resolveMissingImage(map: MapAdapter, imageId: String): Deferred<Unit>? = null

        override fun onGestureActive(map: MapAdapter, active: Boolean) {}

        override fun onViewportChanged(map: MapAdapter) {}
      }

    // The pump loop on the test thread never drains a queued main dispatcher; run inline instead.
    private val runtime =
      createNativeMapRuntime(
        MlnFfiRuntimeOptions(
          cacheFile = Path(cacheDirectory.resolve("cache.db").toString()),
          mainDispatcher = UnconfinedMain,
        )
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
          frameRequested.set(true)
        }

        override fun <T> withRendererAccess(action: () -> T): T = host.withRendererAccess(action)

        override fun enqueueRenderer(action: () -> Unit): Boolean = host.enqueueRenderer(action)
      }

    init {
      renderer.start()
      renderer.onSurfaceAvailable(hostSession)
    }

    fun renderStyle(style: BaseStyle, extent: MapExtent): MlnFfiRenderTarget {
      val expectedStyleLoads = styleLoads + 1
      renderer.setBaseStyle(style)
      val deadline = TimeSource.Monotonic.markNow() + TestTimeout
      var rendered: MlnFfiRenderTarget? = null
      var renderedFrames = 0
      var lastResult: MlnFfiFrameResult? = null
      // Wait for style application before pumping. Discarding a frame rendered during the
      // style callback can consume the only requested frame and leave the map idle forever.
      while (styleLoads < expectedStyleLoads || rendered == null) {
        check(deadline.hasNotPassedNow()) {
          "Timed out rendering style $style at $extent; " +
            "style loads: $styleLoads/$expectedStyleLoads, rendered frames: $renderedFrames, " +
            "last result: $lastResult, failure: $failure"
        }
        failure?.let { error(it) }
        if (styleLoads < expectedStyleLoads) {
          Thread.sleep(PollIntervalMillis)
          continue
        }
        val pumped = pumpFrame(extent)
        lastResult = pumped.result
        if (pumped.rendered) {
          renderedFrames++
          rendered = pumped.target
        }
        Thread.sleep(PollIntervalMillis)
      }
      return checkNotNull(rendered)
    }

    fun pumpUntilRendered(extent: MapExtent): MlnFfiRenderTarget {
      val deadline = TimeSource.Monotonic.markNow() + TestTimeout
      var lastResult: MlnFfiFrameResult? = null
      while (true) {
        check(deadline.hasNotPassedNow()) {
          "Timed out rendering at $extent; last result: $lastResult, failure: $failure"
        }
        failure?.let { error(it) }
        val pumped = pumpFrame(extent)
        lastResult = pumped.result
        if (pumped.rendered) return checkNotNull(pumped.target)
        Thread.sleep(PollIntervalMillis)
      }
    }

    fun renderOnDemand(extent: MapExtent, durationMillis: Long): Int {
      val deadline = System.nanoTime() + durationMillis * 1_000_000
      var rendered = 0
      while (System.nanoTime() < deadline) {
        if (frameRequested.getAndSet(false)) {
          val frame = pumpFrame(extent)
          if (frame.rendered) {
            host.setPresentedTarget(checkNotNull(frame.target))
            rendered++
          }
        }
        Thread.sleep(PollIntervalMillis)
      }
      return rendered
    }

    private fun pumpFrame(extent: MapExtent): PumpedFrame {
      frameRequested.set(false)
      val frame = assertIs<MlnFfiMapFrameAcquisition.Acquired>(host.acquireFrame(extent)).frame
      try {
        val result = host.withProducerAccess(frame) { renderer.render(hostSession, frame) }
        if (result is MlnFfiFrameResult.Rendered) {
          host.completeProducerAccess(frame)
          return PumpedFrame(result, frame.target)
        }
        return PumpedFrame(result, null)
      } finally {
        host.releaseFrame(frame)
      }
    }

    override fun close() {
      renderer.onSurfaceLost(hostSession)
      runtime.close()
      runBlocking { withTimeout(TestTimeout.inWholeMilliseconds) { runtime.awaitClosed() } }
      cacheDirectory.toFile().deleteRecursively()
    }

    private class PumpedFrame(val result: MlnFfiFrameResult, val target: MlnFfiRenderTarget?) {
      val rendered: Boolean
        get() = result is MlnFfiFrameResult.Rendered && target != null
    }
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

    fun recordFrame(host: LinuxOpenGlMapHost, target: MlnFfiRenderTarget): Picture =
      PictureRecorder().use { recorder ->
        val canvas = recorder.beginRecording(0f, 0f, DrawWidth.toFloat(), DrawHeight.toFloat())
        CanvasDrawScope().draw(
          Density(1f),
          LayoutDirection.Ltr,
          canvas.asComposeCanvas(),
          Size(DrawWidth.toFloat(), DrawHeight.toFloat()),
        ) {
          assertTrue(
            host.draw(
              this,
              target,
              MlnFfiMapDestination(0, 0, target.extent.physicalWidth, target.extent.physicalHeight),
            )
          )
        }
        recorder.finishRecordingAsPicture()
      }

    fun replayAndRead(picture: Picture): RgbaPixel {
      destination.canvas.clear(0xff00ff00.toInt())
      destination.canvas.drawPicture(picture)
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
    const val PollIntervalMillis = 8L
    const val ChannelTolerance = 2

    fun near(expected: RgbaPixel, actual: RgbaPixel): Boolean =
      abs(expected.red - actual.red) <= ChannelTolerance &&
        abs(expected.green - actual.green) <= ChannelTolerance &&
        abs(expected.blue - actual.blue) <= ChannelTolerance &&
        abs(expected.alpha - actual.alpha) <= ChannelTolerance

    val TestTimeout = 30.seconds

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
        """
      )
  }
}
