package org.maplibre.compose.desktop.bridge

import java.lang.foreign.Arena
import java.lang.foreign.SymbolLookup
import java.nio.file.Files
import org.lwjgl.PointerBuffer
import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL10
import org.lwjgl.egl.EGL10.EGL_ALPHA_SIZE
import org.lwjgl.egl.EGL10.EGL_BLUE_SIZE
import org.lwjgl.egl.EGL10.EGL_DEPTH_SIZE
import org.lwjgl.egl.EGL10.EGL_EXTENSIONS
import org.lwjgl.egl.EGL10.EGL_FALSE
import org.lwjgl.egl.EGL10.EGL_GREEN_SIZE
import org.lwjgl.egl.EGL10.EGL_HEIGHT
import org.lwjgl.egl.EGL10.EGL_NONE
import org.lwjgl.egl.EGL10.EGL_NO_CONTEXT
import org.lwjgl.egl.EGL10.EGL_NO_DISPLAY
import org.lwjgl.egl.EGL10.EGL_NO_SURFACE
import org.lwjgl.egl.EGL10.EGL_PBUFFER_BIT
import org.lwjgl.egl.EGL10.EGL_RED_SIZE
import org.lwjgl.egl.EGL10.EGL_STENCIL_SIZE
import org.lwjgl.egl.EGL10.EGL_SUCCESS
import org.lwjgl.egl.EGL10.EGL_SURFACE_TYPE
import org.lwjgl.egl.EGL10.EGL_WIDTH
import org.lwjgl.egl.EGL10.eglChooseConfig
import org.lwjgl.egl.EGL10.eglCreateContext
import org.lwjgl.egl.EGL10.eglCreatePbufferSurface
import org.lwjgl.egl.EGL10.eglDestroyContext
import org.lwjgl.egl.EGL10.eglDestroySurface
import org.lwjgl.egl.EGL10.eglGetError
import org.lwjgl.egl.EGL10.eglInitialize
import org.lwjgl.egl.EGL10.eglMakeCurrent
import org.lwjgl.egl.EGL10.eglQueryString
import org.lwjgl.egl.EGL12.EGL_OPENGL_ES_API
import org.lwjgl.egl.EGL12.eglBindAPI
import org.lwjgl.egl.EGL13.EGL_CONTEXT_CLIENT_VERSION
import org.lwjgl.egl.EGL13.EGL_RENDERABLE_TYPE
import org.lwjgl.egl.EGL14
import org.lwjgl.egl.EGL15
import org.lwjgl.egl.EGL15.EGL_OPENGL_ES3_BIT
import org.lwjgl.egl.EGLCapabilities
import org.lwjgl.egl.EXTDeviceEnumeration
import org.lwjgl.egl.EXTPlatformDevice
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GLCapabilities
import org.lwjgl.opengles.APPLETextureFormatBGRA8888.GL_BGRA8_EXT
import org.lwjgl.opengles.GLES
import org.lwjgl.opengles.GLES20.GL_CLAMP_TO_EDGE
import org.lwjgl.opengles.GLES20.GL_LINEAR
import org.lwjgl.opengles.GLES20.GL_NO_ERROR
import org.lwjgl.opengles.GLES20.GL_TEXTURE_2D
import org.lwjgl.opengles.GLES20.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengles.GLES20.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengles.GLES20.GL_TEXTURE_WRAP_S
import org.lwjgl.opengles.GLES20.GL_TEXTURE_WRAP_T
import org.lwjgl.opengles.GLES20.glBindTexture
import org.lwjgl.opengles.GLES20.glDeleteTextures
import org.lwjgl.opengles.GLES20.glFinish
import org.lwjgl.opengles.GLES20.glGenTextures
import org.lwjgl.opengles.GLES20.glGetError
import org.lwjgl.opengles.GLES20.glTexParameteri
import org.lwjgl.opengles.GLESCapabilities
import org.lwjgl.opengles.OESEGLImage.glEGLImageTargetTexture2DOES
import org.lwjgl.system.Configuration
import org.lwjgl.system.FunctionProvider
import org.lwjgl.system.JNI.callPPI
import org.lwjgl.system.JNI.callPPP
import org.lwjgl.system.JNI.callPPPPP
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.MemoryUtil.memAddress
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.TextureOrigin
import org.maplibre.nativeffi.Maplibre

/**
 * Owns an EGL pbuffer and share context. Displays are shared with other maps and host libraries.
 */
internal class DesktopEglContext
private constructor(private val metalDevice: Long?, private val requiredDeviceUuids: Set<String>) :
  AutoCloseable {
  private var display = EGL_NO_DISPLAY
  private var config = NULL
  private var surface = EGL_NO_SURFACE
  private var shareContext = EGL_NO_CONTEXT
  private var glCapabilities: GLCapabilities? = null
  private var glesCapabilities: GLESCapabilities? = null
  private var eglCreateImage = NULL
  private var eglDestroyImage = NULL
  private var eglGetProcAddress = NULL

  val handles: EglContextHandles
    get() =
      EglContextHandles(
        display = NativeHandle(display),
        config = NativeHandle(config),
        shareContext = NativeHandle(shareContext),
        getProcAddress = NativeHandle(eglGetProcAddress),
      )

  fun createImportedTexture(
    metalTexture: NativeHandle,
    extent: MapExtent,
  ): MacAngleImportedTexture = MacAngleImportedTexture.create(this, metalTexture, extent)

  fun makeCurrent() {
    check(
      display != EGL_NO_DISPLAY && surface != EGL_NO_SURFACE && shareContext != EGL_NO_CONTEXT
    ) {
      "EGL context is not initialized"
    }
    check(eglMakeCurrent(display, surface, surface, shareContext), "eglMakeCurrent")
    if (metalDevice != null) {
      glesCapabilities?.let { GLES.setCapabilities(it) }
        ?: run { glesCapabilities = GLES.createCapabilities() }
    } else {
      glCapabilities?.let { GL.setCapabilities(it) }
        ?: run { glCapabilities = GL.createCapabilities() }
    }
  }

  fun waitIdle() {
    if (shareContext == EGL_NO_CONTEXT) {
      return
    }
    makeCurrent()
    if (metalDevice != null) glFinish() else GL11.glFinish()
  }

  internal fun createMetalImage(metalTexture: NativeHandle): Long {
    check(eglCreateImage != NULL) { "EGL_KHR_image_base is not available" }
    MemoryStack.stackPush().use { stack ->
      val attributes = stack.ints(EGL_NONE)
      val image =
        callPPPPP(
          display,
          EGL_NO_CONTEXT,
          EGL_METAL_TEXTURE_ANGLE,
          metalTexture.address,
          memAddress(attributes),
          eglCreateImage,
        )
      if (image == NULL) {
        throw MlnFfiHostException(
          "eglCreateImageKHR(EGL_METAL_TEXTURE_ANGLE) failed with ${eglError()}"
        )
      }
      return image
    }
  }

  internal fun destroyMetalImage(image: Long) {
    if (image != NULL && eglDestroyImage != NULL) {
      check(callPPI(display, image, eglDestroyImage) != EGL_FALSE, "eglDestroyImageKHR")
    }
  }

  override fun close() {
    runCatching { waitIdle() }
    if (glesCapabilities != null) GLES.setCapabilities(null)
    if (glCapabilities != null) GL.setCapabilities(null)
    if (display != EGL_NO_DISPLAY) {
      eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
    }
    if (surface != EGL_NO_SURFACE) {
      eglDestroySurface(display, surface)
      surface = EGL_NO_SURFACE
    }
    if (shareContext != EGL_NO_CONTEXT) {
      eglDestroyContext(display, shareContext)
      shareContext = EGL_NO_CONTEXT
    }
    if (display != EGL_NO_DISPLAY) {
      // EGL displays have process-wide identity, including displays used by Nucleus. Terminating
      // one here would invalidate other maps and host contexts. Only our context/surface are owned.
      display = EGL_NO_DISPLAY
    }
    glCapabilities = null
    glesCapabilities = null
  }

  private fun create() {
    if (metalDevice != null) {
      MacAngleLibraries.load()
      initialize(createMetalDisplay())
      return
    }
    if (runCatching { EGL.getCapabilities() }.isFailure) EGL.create()
    // Mesa's surfaceless platform picks the first render node, which on hybrid-graphics machines
    // may not be the device Compose renders on. Try each display until one is on that device.
    var failure: Throwable? = null
    val tried = mutableSetOf<Long>()
    for (candidate in linuxDisplayCandidates()) {
      val display = runCatching(candidate).getOrDefault(EGL_NO_DISPLAY)
      if (display == EGL_NO_DISPLAY || !tried.add(display)) continue
      try {
        initialize(display)
        if (rendersOnRequiredDevice()) return
      } catch (error: RuntimeException) {
        failure = error
      }
      close()
    }
    throw MlnFfiHostException("No EGL display renders on the map's graphics device", failure)
  }

  private fun rendersOnRequiredDevice(): Boolean {
    if (requiredDeviceUuids.isEmpty()) return true
    val uuids = currentOpenGlDeviceUuids()
    return uuids.isEmpty() || uuids.any { it in requiredDeviceUuids }
  }

  private fun initialize(display: Long) {
    check(display != EGL_NO_DISPLAY) { "EGL returned no display" }
    this.display = display
    val displayCapabilities = EglDisplays.initialize(display)
    eglCreateImage = displayCapabilities.eglCreateImageKHR
    eglDestroyImage = displayCapabilities.eglDestroyImageKHR
    eglGetProcAddress = EGL.getFunctionProvider().getFunctionAddress("eglGetProcAddress")

    val extensions = eglQueryString(display, EGL_EXTENSIONS).orEmpty()
    if (metalDevice != null)
      check("EGL_ANGLE_metal_texture_client_buffer" in extensions) {
        "ANGLE EGL display does not expose EGL_ANGLE_metal_texture_client_buffer"
      }
    if (metalDevice != null)
      check(displayCapabilities.EGL_KHR_image_base || displayCapabilities.EGL_KHR_image) {
        "ANGLE EGL display does not expose EGL_KHR_image_base"
      }
    check(
      eglBindAPI(if (metalDevice != null) EGL_OPENGL_ES_API else EGL14.EGL_OPENGL_API),
      "eglBindAPI",
    )
    chooseConfig()
    createShareContext()
    createPbuffer()
    makeCurrent()
  }

  /** Display factories in preference order; each may throw or return [EGL_NO_DISPLAY]. */
  private fun linuxDisplayCandidates(): Sequence<() -> Long> = sequence {
    yield {
      EGL15.eglGetPlatformDisplay(
        EGL_PLATFORM_SURFACELESS_MESA,
        EGL14.EGL_DEFAULT_DISPLAY,
        null as PointerBuffer?,
      )
    }
    yield { EGL10.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY) }
    val client = EGL.getCapabilities()
    if (client.EGL_EXT_device_enumeration && client.EGL_EXT_platform_device) {
      for (device in enumerateDevices()) {
        yield {
          EGL15.eglGetPlatformDisplay(
            EXTPlatformDevice.EGL_PLATFORM_DEVICE_EXT,
            device,
            null as PointerBuffer?,
          )
        }
      }
    }
  }

  private fun enumerateDevices(): List<Long> =
    MemoryStack.stackPush().use { stack ->
      val count = stack.mallocInt(1)
      if (!EXTDeviceEnumeration.eglQueryDevicesEXT(null, count) || count[0] <= 0) {
        return emptyList()
      }
      val devices = stack.mallocPointer(count[0])
      if (!EXTDeviceEnumeration.eglQueryDevicesEXT(devices, count)) return emptyList()
      List(count[0]) { devices[it] }
    }

  private fun createMetalDisplay(): Long =
    MemoryStack.stackPush().use { stack ->
      val attributes =
        mutableListOf(
          EGL_PLATFORM_ANGLE_TYPE_ANGLE,
          EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE,
          EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE,
          EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE,
        )
      if (metalDevice != 0L) {
        val registryId = ObjectiveC.sendLong(checkNotNull(metalDevice), "registryID")
        attributes.addAll(
          listOf(
            EGL_PLATFORM_ANGLE_DEVICE_ID_HIGH_ANGLE,
            (registryId ushr 32).toInt(),
            EGL_PLATFORM_ANGLE_DEVICE_ID_LOW_ANGLE,
            registryId.toInt(),
          )
        )
      }
      attributes.add(EGL_NONE)
      val nativeAttributes = stack.ints(*attributes.toIntArray())
      val function = EGL.getCapabilities().eglGetPlatformDisplayEXT
      check(function != NULL) { "EGL_EXT_platform_base is not available" }
      val result = callPPP(EGL_PLATFORM_ANGLE_ANGLE, NULL, memAddress(nativeAttributes), function)
      check(result != EGL_NO_DISPLAY) { "eglGetPlatformDisplayEXT(ANGLE Metal) failed" }
      result
    }

  private fun chooseConfig() {
    MemoryStack.stackPush().use { stack ->
      val attributes =
        stack.ints(
          EGL_SURFACE_TYPE,
          EGL_PBUFFER_BIT,
          EGL_RENDERABLE_TYPE,
          if (metalDevice != null) EGL_OPENGL_ES3_BIT else EGL14.EGL_OPENGL_BIT,
          EGL_RED_SIZE,
          8,
          EGL_GREEN_SIZE,
          8,
          EGL_BLUE_SIZE,
          8,
          EGL_ALPHA_SIZE,
          8,
          EGL_DEPTH_SIZE,
          24,
          EGL_STENCIL_SIZE,
          8,
          EGL_NONE,
        )
      val configs = stack.mallocPointer(1)
      val count = stack.mallocInt(1)
      check(eglChooseConfig(display, attributes, configs, count), "eglChooseConfig")
      check(count[0] > 0 && configs[0] != NULL) {
        "No EGL config supports the requested pbuffer rendering API"
      }
      config = configs[0]
    }
  }

  private fun createShareContext() {
    MemoryStack.stackPush().use { stack ->
      val attributes =
        if (metalDevice != null) stack.ints(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE)
        else stack.ints(EGL_NONE)
      shareContext = eglCreateContext(display, config, EGL_NO_CONTEXT, attributes)
      check(shareContext != EGL_NO_CONTEXT) { "eglCreateContext failed with ${eglError()}" }
    }
  }

  private fun createPbuffer() {
    MemoryStack.stackPush().use { stack ->
      val attributes = stack.ints(EGL_WIDTH, 8, EGL_HEIGHT, 8, EGL_NONE)
      surface = eglCreatePbufferSurface(display, config, attributes)
      check(surface != EGL_NO_SURFACE) { "eglCreatePbufferSurface failed with ${eglError()}" }
    }
  }

  private fun check(status: Boolean, operation: String) {
    check(status) { "$operation failed with ${eglError()}" }
  }

  private fun eglError(): String {
    val error = eglGetError()
    return if (error == EGL_SUCCESS) "EGL_SUCCESS" else "EGL error 0x${error.toString(16)}"
  }

  companion object {
    private const val EGL_PLATFORM_SURFACELESS_MESA = 0x31DD
    private const val EGL_PLATFORM_ANGLE_DEVICE_ID_HIGH_ANGLE = 0x34D6
    private const val EGL_PLATFORM_ANGLE_DEVICE_ID_LOW_ANGLE = 0x34D7
    private const val EGL_PLATFORM_ANGLE_ANGLE = 0x3202
    private const val EGL_PLATFORM_ANGLE_TYPE_ANGLE = 0x3203
    private const val EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE = 0x3209
    private const val EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE = 0x320A
    private const val EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE = 0x3489
    private const val EGL_METAL_TEXTURE_ANGLE = 0x34A7

    /**
     * On Linux, [requiredDeviceUuids] restricts the display to one whose OpenGL device UUID is in
     * the set, so exported Vulkan memory can be imported into the producer context.
     */
    fun create(
      metalDevice: Long? = null,
      requiredDeviceUuids: Set<String> = emptySet(),
    ): DesktopEglContext {
      val context = DesktopEglContext(metalDevice, requiredDeviceUuids)
      try {
        context.create()
        return context
      } catch (error: RuntimeException) {
        runCatching { context.close() }.exceptionOrNull()?.let(error::addSuppressed)
        throw error
      }
    }
  }
}

internal class MacAngleImportedTexture
private constructor(
  private val context: DesktopEglContext,
  private val metalTexture: NativeHandle,
  private val extent: MapExtent,
) : AutoCloseable {
  private var image = NULL
  private var texture = 0

  fun target(generation: Long): OpenGlTextureTarget =
    OpenGlTextureTarget(
      context = context.handles,
      textureName = texture,
      textureTarget = GL_TEXTURE_2D,
      format = GL_BGRA8_EXT,
      makeContextCurrent = { context.makeCurrent() },
      origin = TextureOrigin.BOTTOM_LEFT,
      extent = extent,
      generation = generation,
    )

  private fun create() {
    context.makeCurrent()
    image = context.createMetalImage(metalTexture)
    texture = glGenTextures()
    checkGl("glGenTextures")
    glBindTexture(GL_TEXTURE_2D, texture)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image)
    checkGl("glEGLImageTargetTexture2DOES")
    glBindTexture(GL_TEXTURE_2D, 0)
  }

  override fun close() {
    context.makeCurrent()
    glFinish()
    if (texture != 0) {
      glDeleteTextures(texture)
      texture = 0
    }
    if (image != NULL) {
      context.destroyMetalImage(image)
      image = NULL
    }
  }

  companion object {
    fun create(
      context: DesktopEglContext,
      metalTexture: NativeHandle,
      extent: MapExtent,
    ): MacAngleImportedTexture {
      val texture = MacAngleImportedTexture(context, metalTexture, extent)
      try {
        texture.create()
        return texture
      } catch (error: RuntimeException) {
        texture.close()
        throw error
      }
    }

    private fun checkGl(operation: String) {
      val error = glGetError()
      check(error == GL_NO_ERROR) { "$operation failed with GL error 0x${error.toString(16)}" }
    }
  }
}

/** Loads one ANGLE instance from the Compose runtime artifact for EGL and GLES. */
private object MacAngleLibraries {
  private var loaded = false

  @Synchronized
  fun load() {
    if (loaded) return
    Maplibre.loadNativeLibrary()
    val root = Files.createTempDirectory("maplibre-compose-angle-")
    root.toFile().deleteOnExit()
    for (name in listOf("libGLESv2.dylib", "libEGL.dylib")) {
      val resource = "/META-INF/maplibre-compose/angle/macos-arm64/$name"
      val stream =
        checkNotNull(MacAngleLibraries::class.java.getResourceAsStream(resource)) {
          "ANGLE is missing. Package maplibre-compose-runtime-opengl-macos-arm64."
        }
      val path = root.resolve(name)
      stream.use { Files.copy(it, path) }
      path.toFile().deleteOnExit()
    }
    for (name in listOf("libGLESv2.dylib", "libEGL.dylib")) System.load(
      root.resolve(name).toString()
    )
    fun provider(name: String): FunctionProvider {
      val lookup =
        SymbolLookup.libraryLookup(
          root.resolve(name),
          Arena.global(),
        )
      return FunctionProvider { symbol ->
        lookup
          .find(MemoryUtil.memUTF8(MemoryUtil.memAddress(symbol)))
          .map { it.address() }
          .orElse(0L)
      }
    }
    Configuration.EGL_EXPLICIT_INIT.set(true)
    Configuration.OPENGLES_EXPLICIT_INIT.set(true)
    EGL.create(provider("libEGL.dylib"))
    GLES.create(provider("libGLESv2.dylib"))
    loaded = true
  }
}

/** Initializes each process-wide EGL display once without taking ownership from the host. */
private object EglDisplays {
  private val displays = mutableMapOf<Long, EGLCapabilities>()

  @Synchronized
  fun initialize(display: Long): EGLCapabilities =
    displays.getOrPut(display) {
      val major = IntArray(1)
      val minor = IntArray(1)
      check(eglInitialize(display, major, minor)) { "eglInitialize failed: ${eglGetError()}" }
      EGL.createDisplayCapabilities(display, major[0], minor[0])
    }
}
