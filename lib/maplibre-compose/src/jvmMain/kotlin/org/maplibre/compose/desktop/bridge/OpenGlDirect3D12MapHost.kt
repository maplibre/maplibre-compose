package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.skia.SurfaceColorFormat
import org.lwjgl.PointerBuffer
import org.lwjgl.opengl.EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT
import org.lwjgl.opengl.EXTMemoryObject.GL_OPTIMAL_TILING_EXT
import org.lwjgl.opengl.EXTMemoryObject.GL_TEXTURE_TILING_EXT
import org.lwjgl.opengl.EXTMemoryObject.glCreateMemoryObjectsEXT
import org.lwjgl.opengl.EXTMemoryObject.glDeleteMemoryObjectsEXT
import org.lwjgl.opengl.EXTMemoryObject.glMemoryObjectParameteriEXT
import org.lwjgl.opengl.EXTMemoryObject.glTexStorageMem2DEXT
import org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D12_RESOURCE_EXT
import org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_RGBA8
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_TRUE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glFinish
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.WGL
import org.lwjgl.opengl.WGLNVGPUAffinity
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.windows.GDI32
import org.lwjgl.system.windows.PIXELFORMATDESCRIPTOR
import org.lwjgl.system.windows.User32
import org.lwjgl.system.windows.WNDCLASSEX
import org.lwjgl.system.windows.WinBase
import org.lwjgl.system.windows.WindowProc
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.desktop.Direct3D12ComposeGpuContext
import org.maplibre.compose.desktop.onGpuThread
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiMapHost
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.TextureOrigin
import org.maplibre.compose.mlnffi.WglContextHandles

/** Bridges MapLibre's OpenGL rendering into Compose's Direct3D 12 context on Windows. */
internal class OpenGlDirect3D12MapHost(private val gpuHost: ComposeMapHost) : MlnFfiMapHost {
  private val rendererThread = MapRendererThread("maplibre-windows-opengl-renderer")
  private val presenter = Direct3D12Presenter(gpuHost)
  private val frameCompletion = ComposeFrameCompletion()
  private var wgl: WindowsWglContext? = null
  private var direct3DTexture = NativeHandle(0)
  private var importedTexture: WindowsWglImportedDirect3DTexture? = null
  private val retiredTextures = mutableMapOf<Long, Direct3DTextureTarget>()
  private var generation = 0L
  private var currentExtent = MapExtent.Empty
  private var currentDevice = NativeHandle(0)

  override val backends: RenderBackendPair =
    RenderBackendPair(MapRenderBackend.OPENGL, ComposeRenderBackend.DIRECT3D12)

  override fun resize(extent: MapExtent) {
    val device = if (extent.isEmpty) null else currentDeviceOrNull() ?: return
    resize(extent, device)
  }

  private fun resize(extent: MapExtent, device: NativeHandle?) {
    val result = rendererThread.run { resizeOnRendererThread(extent, device) }
    if (result.failure != null) {
      result.retired.forEach { releaseDirect3DTexture(it.texture) }
      throw result.failure
    }
    result.retired.singleOrNull()?.let { retiredTextures[it.generation] = it }
  }

  private fun resizeOnRendererThread(extent: MapExtent, device: NativeHandle?): ResizeResult {
    if (extent == currentExtent && importedTexture != null && device == currentDevice) {
      return ResizeResult()
    }

    val deviceChanged = !currentDevice.isNull && device != currentDevice
    val retired = mutableListOf<Direct3DTextureTarget>()
    retireTexture()?.let(retired::add)
    if (deviceChanged) {
      val closing = wgl
      wgl = null
      closing?.close()
    }
    try {
      recreateTexture(extent, device)
    } catch (error: Throwable) {
      retireTexture()?.let(retired::add)
      return ResizeResult(retired, error)
    }
    currentExtent = extent
    generation += 1
    return ResizeResult(retired)
  }

  override fun acquireFrame(
    frameId: Long,
    extent: MapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrameAcquisition {
    val context = withPreparedContext { it } ?: return MlnFfiMapFrameAcquisition.NotReady
    val device = context.device
    if (importedTexture == null || extent != currentExtent || device != currentDevice) {
      resize(extent, device)
    }
    return MlnFfiMapFrameAcquisition.Acquired(
      MlnFfiMapFrame(
        frameId = frameId,
        extent = extent,
        target = target(generation),
        presentationTimeNanos = presentationTimeNanos,
      )
    )
  }

  override fun completeProducerAccess(frame: MlnFfiMapFrame) {
    // The borrowed-texture render path finishes MapLibre's context. Switching back to the host
    // context and finishing it establishes a conservative boundary for drivers that defer shared
    // object bookkeeping until the importing context runs again.
    rendererThread.run {
      wgl?.let {
        it.makeCurrent()
        glFinish()
      }
    }
  }

  override fun <T> withProducerAccess(frame: MlnFfiMapFrame, action: () -> T): T =
    rendererThread.run(action)

  override fun <T> withRendererAccess(action: () -> T): T = rendererThread.run(action)

  override fun enqueueRenderer(action: () -> Unit): Boolean = rendererThread.post(action)

  override fun draw(scope: DrawScope, target: MlnFfiRenderTarget): Boolean {
    if (target !is OpenGlTextureTarget) return false
    val direct3DTarget =
      if (target.generation == generation) presentationTarget()
      else retiredTextures[target.generation]
    if (direct3DTarget == null) return false
    val drew =
      withPreparedContext { context ->
        presenter.draw(scope, context.skiaContext, direct3DTarget, frameCompletion)
      } ?: false
    if (drew) disposeRetiredTextures(exceptGeneration = target.generation)
    return drew
  }

  override fun close() {
    try {
      frameCompletion.abandon()
      val current = rendererThread.run { retireTexture() }
      current?.let { releaseDirect3DTexture(it.texture) }
      disposeRetiredTextures()
      presenter.close()
    } finally {
      val closingWgl = wgl
      wgl = null
      try {
        rendererThread.run { closingWgl?.close() }
      } finally {
        rendererThread.close()
      }
    }
  }

  private fun target(generation: Long): MlnFfiRenderTarget =
    checkNotNull(importedTexture) { "Windows WGL texture is not initialized" }.target(generation)

  private fun recreateTexture(extent: MapExtent, device: NativeHandle?) {
    if (extent.isEmpty) return

    val direct3DDevice =
      checkNotNull(device) { "resize() resolves the Direct3D device before this hop" }
    direct3DTexture =
      WindowsDirect3DInterop.createSharedTexture(
        direct3DDevice,
        extent,
        DXGI_FORMAT_R8G8B8A8_UNORM,
      )
    currentDevice = direct3DDevice
    var sharedHandle = NULL
    try {
      sharedHandle = WindowsDirect3DInterop.createSharedHandle(direct3DTexture)
      importedTexture = importTexture(sharedHandle, extent)
    } finally {
      // EXT_external_objects_win32 does not take ownership of an imported NT handle.
      WindowsDirect3DInterop.closeSharedHandle(sharedHandle)
    }
  }

  private fun importTexture(
    sharedHandle: Long,
    extent: MapExtent,
  ): WindowsWglImportedDirect3DTexture {
    val current = wgl
    if (current != null) {
      current.tryImportDirect3DTexture(sharedHandle, extent)?.let {
        return it
      }
      current.close()
      wgl = null
    }

    val selected = WindowsWglContext.createCompatibleImport(sharedHandle, extent)
    wgl = selected.context
    return selected.texture
  }

  /** Detaches the current GL import while retaining its D3D presentation resource. */
  private fun retireTexture(): Direct3DTextureTarget? {
    val retired = presentationTarget()
    importedTexture?.close()
    importedTexture = null
    direct3DTexture = NativeHandle(0)
    currentDevice = NativeHandle(0)
    return retired
  }

  private fun currentDeviceOrNull(): NativeHandle? = withPreparedContext { it.device }

  private fun <T> withPreparedContext(action: (Direct3D12ComposeGpuContext) -> T): T? =
    gpuHost.onGpuThread {
      val context = gpuHost.gpuContext() ?: return@onGpuThread null
      val direct3DContext =
        context as? Direct3D12ComposeGpuContext
          ?: throw MlnFfiHostException(
            "${gpuHost.description} switched from Direct3D12ComposeGpuContext to " +
              context::class.simpleName
          )
      frameCompletion.prepare(direct3DContext.skiaContext, presenter::resetContext)
      action(direct3DContext)
    }

  private fun presentationTarget(): Direct3DTextureTarget? {
    if (direct3DTexture.isNull) return null
    return Direct3DTextureTarget(
      texture = direct3DTexture,
      format = DXGI_FORMAT_R8G8B8A8_UNORM,
      colorFormat = SurfaceColorFormat.RGBA_8888,
      origin = TextureOrigin.BOTTOM_LEFT,
      extent = importedTexture?.extent ?: currentExtent,
      generation = generation,
    )
  }

  private fun disposeRetiredTextures(exceptGeneration: Long? = null) {
    val iterator = retiredTextures.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (entry.key != exceptGeneration) {
        releaseDirect3DTexture(entry.value.texture)
        iterator.remove()
      }
    }
  }

  private fun releaseDirect3DTexture(texture: NativeHandle) {
    if (texture.isNull) return
    presenter.forget(texture)
    WindowsDirect3DInterop.release(texture)
  }

  private data class ResizeResult(
    val retired: List<Direct3DTextureTarget> = emptyList(),
    val failure: Throwable? = null,
  )
}

/** A WGL context and a D3D12 texture that it successfully imported. */
private data class WindowsWglImport(
  val context: WindowsWglContext,
  val texture: WindowsWglImportedDirect3DTexture,
)

/** The host WGL context whose share group contains MapLibre's imported texture. */
private class WindowsWglContext
private constructor(private val kind: Kind, private val label: String) : AutoCloseable {
  private val windowClassName = "$WINDOW_CLASS_PREFIX-${nextWindowClassId.incrementAndGet()}"
  private var window = NULL
  private var deviceContext = NULL
  private var shareContext = NULL
  private var module = NULL
  private var windowClassAtom: Short = 0
  private var windowProc: WindowProc? = null
  private var capabilities: org.lwjgl.opengl.GLCapabilities? = null

  val handles: WglContextHandles
    get() {
      val getProcAddress =
        checkNotNull(GL.getFunctionProvider()).getFunctionAddress("wglGetProcAddress")
      check(getProcAddress != NULL) { "$label does not export wglGetProcAddress" }
      return WglContextHandles(
        deviceContext = NativeHandle(deviceContext),
        shareContext = NativeHandle(shareContext),
        // Keep MapLibre's context in the same OpenGL implementation LWJGL loaded. This matters
        // when an application ships Mesa's opengl32.dll alongside the system implementation.
        getProcAddress = NativeHandle(getProcAddress),
      )
    }

  fun makeCurrent() {
    check(deviceContext != NULL && shareContext != NULL) { "$label is not initialized" }
    check(WGL.wglMakeCurrent(null, deviceContext, shareContext)) {
      "wglMakeCurrent failed for $label"
    }
    val currentCapabilities = capabilities
    if (currentCapabilities == null) {
      GL.setCapabilities(null)
      capabilities = GL.createCapabilities()
    } else {
      GL.setCapabilities(currentCapabilities)
    }
  }

  fun tryImportDirect3DTexture(
    sharedHandle: Long,
    extent: MapExtent,
  ): WindowsWglImportedDirect3DTexture? =
    try {
      WindowsWglImportedDirect3DTexture.create(this, sharedHandle, extent)
    } catch (_: RuntimeException) {
      null
    }

  private fun createWindowContext() {
    val proc = WindowProc.create { hwnd, message, wParam, lParam ->
      User32.DefWindowProc(hwnd, message, wParam, lParam)
    }
    windowProc = proc
    module = WinBase.GetModuleHandle(null, null as CharSequence?)
    check(module != NULL) { "GetModuleHandle failed for $label" }

    MemoryStack.stackPush().use { stack ->
      val windowClass =
        WNDCLASSEX.calloc(stack)
          .cbSize(WNDCLASSEX.SIZEOF)
          .style(User32.CS_OWNDC)
          .lpfnWndProc(proc)
          .hInstance(module)
          .lpszClassName(stack.UTF16(windowClassName))
      windowClassAtom = User32.RegisterClassEx(null, windowClass)
      check(windowClassAtom.toInt() != 0) { "RegisterClassEx failed for $label" }
    }

    window =
      User32.CreateWindowEx(
        null,
        0,
        windowClassName,
        "MapLibre Compose WGL",
        User32.WS_POPUP,
        0,
        0,
        8,
        8,
        NULL,
        NULL,
        module,
        NULL,
      )
    check(window != NULL) { "CreateWindowEx failed for $label" }
    deviceContext = User32.GetDC(window)
    check(deviceContext != NULL) { "GetDC failed for $label" }

    MemoryStack.stackPush().use { stack ->
      val descriptor =
        PIXELFORMATDESCRIPTOR.calloc(stack)
          .nSize(PIXELFORMATDESCRIPTOR.SIZEOF.toShort())
          .nVersion(1)
          .dwFlags(GDI32.PFD_DRAW_TO_WINDOW or GDI32.PFD_SUPPORT_OPENGL)
          .iPixelType(GDI32.PFD_TYPE_RGBA)
          .cColorBits(32)
          .cAlphaBits(8)
          .iLayerType(GDI32.PFD_MAIN_PLANE)
      val pixelFormat = GDI32.ChoosePixelFormat(null, deviceContext, descriptor)
      check(pixelFormat != 0) { "ChoosePixelFormat failed for $label" }
      check(GDI32.SetPixelFormat(null, deviceContext, pixelFormat, descriptor)) {
        "SetPixelFormat failed for $label"
      }
    }

    shareContext = WGL.wglCreateContext(null, deviceContext)
    check(shareContext != NULL) { "wglCreateContext failed for $label" }
    makeCurrent()
  }

  private fun createAffinityContext(gpu: Long) {
    MemoryStack.stackPush().use { stack ->
      val affinityMask: PointerBuffer = stack.mallocPointer(2)
      affinityMask.put(0, gpu)
      affinityMask.put(1, NULL)
      deviceContext = WGLNVGPUAffinity.wglCreateAffinityDCNV(affinityMask)
      check(deviceContext != NULL) { "wglCreateAffinityDCNV failed for $label" }
      shareContext = WGL.wglCreateContext(null, deviceContext)
      check(shareContext != NULL) { "wglCreateContext failed for $label" }
      makeCurrent()
    }
  }

  private fun findAffinityImport(
    sharedHandle: Long,
    extent: MapExtent,
  ): WindowsWglImport? {
    makeCurrent()
    val wglCapabilities = runCatching {
      GL.getCapabilitiesWGL()
    }
      .getOrElse { GL.createCapabilitiesWGL() }
    if (!wglCapabilities.WGL_NV_gpu_affinity) return null

    MemoryStack.stackPush().use { stack ->
      val gpuOut = stack.mallocPointer(1)
      var index = 0
      while (true) {
        makeCurrent()
        if (!WGLNVGPUAffinity.wglEnumGpusNV(index, gpuOut)) return null
        val candidate = WindowsWglContext(Kind.NV_AFFINITY, "NVIDIA affinity GPU $index")
        var selected = false
        try {
          candidate.createAffinityContext(gpuOut[0])
          val texture = candidate.tryImportDirect3DTexture(sharedHandle, extent)
          if (texture != null) {
            selected = true
            return WindowsWglImport(candidate, texture)
          }
        } catch (_: RuntimeException) {
          // Try the next adapter; the shared-handle import is the compatibility probe.
        } finally {
          if (!selected) candidate.close()
        }
        index += 1
      }
    }
  }

  override fun close() {
    if (shareContext != NULL) {
      runCatching {
        makeCurrent()
        glFinish()
      }
      WGL.wglMakeCurrent(null, NULL, NULL)
      WGL.wglDeleteContext(null, shareContext)
      shareContext = NULL
    }
    GL.setCapabilities(null)
    capabilities = null

    when (kind) {
      Kind.WINDOW -> {
        if (window != NULL && deviceContext != NULL) User32.ReleaseDC(window, deviceContext)
        deviceContext = NULL
        if (window != NULL) User32.DestroyWindow(null, window)
        window = NULL
        if (windowClassAtom.toInt() != 0) {
          User32.UnregisterClass(null, windowClassName, module)
          windowClassAtom = 0
        }
        windowProc?.free()
        windowProc = null
        module = NULL
      }
      Kind.NV_AFFINITY -> {
        if (deviceContext != NULL) WGLNVGPUAffinity.wglDeleteDCNV(deviceContext)
        deviceContext = NULL
      }
    }
  }

  private enum class Kind {
    WINDOW,
    NV_AFFINITY,
  }

  companion object {
    private const val WINDOW_CLASS_PREFIX = "MapLibreComposeWglBridge"
    private val nextWindowClassId = AtomicLong()

    fun createCompatibleImport(sharedHandle: Long, extent: MapExtent): WindowsWglImport {
      val defaultContext = WindowsWglContext(Kind.WINDOW, "default Windows WGL context")
      try {
        defaultContext.createWindowContext()
        defaultContext.tryImportDirect3DTexture(sharedHandle, extent)?.let {
          return WindowsWglImport(defaultContext, it)
        }

        val affinity = defaultContext.findAffinityImport(sharedHandle, extent)
        if (affinity != null) {
          defaultContext.close()
          return affinity
        }
        throw MlnFfiHostException(
          "No WGL context could import Compose's D3D12 shared texture. The WGL context " +
            "must expose GL_EXT_memory_object and GL_EXT_memory_object_win32 on the same " +
            "graphics adapter as Compose."
        )
      } catch (error: Throwable) {
        defaultContext.close()
        throw error
      }
    }
  }
}

/** A D3D12 resource imported as an OpenGL texture in [context]. */
private class WindowsWglImportedDirect3DTexture
private constructor(
  private val context: WindowsWglContext,
  private val sharedHandle: Long,
  val extent: MapExtent,
) : AutoCloseable {
  private var memoryObject = 0
  private var textureName = 0

  fun target(generation: Long): OpenGlTextureTarget =
    OpenGlTextureTarget(
      context = context.handles,
      textureName = textureName,
      textureTarget = GL_TEXTURE_2D,
      format = GL_RGBA8,
      origin = TextureOrigin.BOTTOM_LEFT,
      makeContextCurrent = context::makeCurrent,
      extent = extent,
      generation = generation,
    )

  private fun create() {
    context.makeCurrent()
    clearGlErrors()
    val capabilities = checkNotNull(GL.getCapabilities())
    check(capabilities.GL_EXT_memory_object) {
      "Windows WGL context does not expose GL_EXT_memory_object"
    }
    check(capabilities.GL_EXT_memory_object_win32) {
      "Windows WGL context does not expose GL_EXT_memory_object_win32"
    }

    memoryObject = glCreateMemoryObjectsEXT()
    glMemoryObjectParameteriEXT(memoryObject, GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE)
    // The EXT specification ignores size for D3D12 resources and recommends zero for drivers
    // that incorrectly validate it.
    glImportMemoryWin32HandleEXT(memoryObject, 0, GL_HANDLE_TYPE_D3D12_RESOURCE_EXT, sharedHandle)
    checkGl("glImportMemoryWin32HandleEXT")

    textureName = glGenTextures()
    glBindTexture(GL_TEXTURE_2D, textureName)
    try {
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_TILING_EXT, GL_OPTIMAL_TILING_EXT)
      glTexStorageMem2DEXT(
        GL_TEXTURE_2D,
        1,
        GL_RGBA8,
        extent.physicalWidth,
        extent.physicalHeight,
        memoryObject,
        0,
      )
      checkGl("glTexStorageMem2DEXT")
    } finally {
      glBindTexture(GL_TEXTURE_2D, 0)
    }
  }

  override fun close() {
    context.makeCurrent()
    glFinish()
    if (textureName != 0) {
      glDeleteTextures(textureName)
      textureName = 0
    }
    if (memoryObject != 0) {
      glDeleteMemoryObjectsEXT(memoryObject)
      memoryObject = 0
    }
  }

  companion object {
    fun create(
      context: WindowsWglContext,
      sharedHandle: Long,
      extent: MapExtent,
    ): WindowsWglImportedDirect3DTexture {
      val texture = WindowsWglImportedDirect3DTexture(context, sharedHandle, extent)
      try {
        texture.create()
        return texture
      } catch (error: Throwable) {
        runCatching { texture.close() }
        throw error
      }
    }
  }
}
