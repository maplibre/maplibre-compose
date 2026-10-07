@file:OptIn(org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import org.jetbrains.skia.SurfaceColorFormat
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME
import org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR
import org.lwjgl.vulkan.KHRExternalMemoryWin32.vkGetMemoryWin32HandlePropertiesKHR
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL
import org.lwjgl.vulkan.VK10.VK_SUCCESS
import org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT
import org.lwjgl.vulkan.VkMemoryWin32HandlePropertiesKHR
import org.maplibre.compose.desktop.Direct3D12ComposeGpuContext
import org.maplibre.compose.desktop.Direct3D12PresentationHost
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.TextureOrigin

internal const val DXGI_FORMAT_B8G8R8A8_UNORM: Int = 87
private const val DXGI_FORMAT_R8G8B8A8_UNORM: Int = 28

/** An `ID3D12Resource` texture to composite into Compose's scene. */
internal data class Direct3DTextureTarget(
  /** `ID3D12Resource`. */
  val texture: NativeHandle,
  /** `DXGI_FORMAT` of [texture]. */
  val format: Int = DXGI_FORMAT_B8G8R8A8_UNORM,
  /** How Skia should interpret [format]. */
  val colorFormat: SurfaceColorFormat = SurfaceColorFormat.BGRA_8888,
  /** Row order of [texture]. */
  val origin: TextureOrigin = TextureOrigin.TopLeft,
  /** The size [texture] was allocated at. */
  val extent: MapExtent,
  /**
   * The [org.maplibre.compose.mlnffi.MlnFfiRenderTarget.generation] this texture corresponds to.
   */
  val generation: Long,
)

/**
 * Bridges MapLibre's Vulkan or OpenGL rendering into Compose's Direct3D 12 context on Windows.
 *
 * Vulkan uses BGRA textures; OpenGL uses RGBA textures. The presenter uses the matching format and
 * texture origin.
 */
internal class Direct3D12MapHost(
  presentationHost: Direct3D12PresentationHost,
  producer: MapRenderBackend = MapRenderBackend.Vulkan,
) :
  SharedTextureMapHost<Direct3D12ComposeGpuContext, Direct3D12MapHost.SharedTexture>(
    presentationHost,
    RenderBackendPair(producer, ComposeRenderBackend.Direct3D12),
    "maplibre-windows-map-renderer",
  ) {
  private val presenter = SkiaTexturePresenter(Direct3DTextureWrapper)
  private val deviceChange =
    DeviceChangeRecovery<NativeHandle>(
      "Compose changed Direct3D devices; recreating the map renderer"
    )
  private var vulkan: VulkanDevice? = null
  private var wgl: WindowsWglContext? = null

  private val dxgiFormat =
    if (producer == MapRenderBackend.OpenGl) DXGI_FORMAT_R8G8B8A8_UNORM
    else DXGI_FORMAT_B8G8R8A8_UNORM

  override fun acquireFrame(extent: MapExtent): MlnFfiMapFrameAcquisition {
    val device = withPreparedContext { it.device } ?: return MlnFfiMapFrameAcquisition.NotReady
    val current = textures.current
    val deviceChanged = deviceChange.changed(current?.device, device)
    if (current == null || current.presentation.extent != extent || deviceChanged) {
      reallocate(extent, device, deviceChanged)
    }
    val texture = checkNotNull(textures.current) { "Windows map texture is not initialized" }
    return MlnFfiMapFrameAcquisition.Acquired(
      MlnFfiMapFrame(target = texture.target(textures.generation))
    )
  }

  /**
   * Replaces the current texture. The producer's view of it is closed on the renderer thread. After
   * a failure the previous texture has no producer view, so it is released on this thread; see
   * [release].
   */
  private fun reallocate(extent: MapExtent, device: NativeHandle, deviceChanged: Boolean) {
    val previous = textures.current
    val generation = textures.generation + 1
    val created = rendererThread.run {
      previous?.closeImported()
      if (deviceChanged) closeProducers()
      runCatching { if (extent.isEmpty) null else createTexture(extent, device, generation) }
    }
    created.onFailure { textures.takeCurrent()?.let(::release) }
    // The previous texture stays presentable until Compose has drawn a newer generation.
    textures.replaceCurrent(created.getOrThrow())
  }

  /** Allocates a texture on [device] and opens it for the producer. Runs on the renderer thread. */
  private fun createTexture(
    extent: MapExtent,
    device: NativeHandle,
    generation: Long,
  ): SharedTexture {
    val texture = WindowsDirect3DInterop.createSharedTexture(device, extent, dxgiFormat)
    try {
      val imported = importTexture(texture, device, extent)
      return SharedTexture(presentationTarget(texture, extent, generation), device, imported)
    } catch (error: Throwable) {
      // Compose never drew this texture, so Skia holds no wrapper to drop on the GPU thread first.
      WindowsDirect3DInterop.release(texture)
      throw error
    }
  }

  /** Opens [texture] for the producer. Runs on the renderer thread. */
  private fun importTexture(
    texture: NativeHandle,
    device: NativeHandle,
    extent: MapExtent,
  ): ImportedMapTexture {
    var sharedHandle = NULL
    try {
      sharedHandle = WindowsDirect3DInterop.createSharedHandle(texture)
      // The shared handle doubles as the probe for picking an importing Vulkan device, so the
      // context cannot be created before there is a texture to share.
      return if (producer == MapRenderBackend.OpenGl) {
        val context = wgl ?: WindowsWglContext.create().also { wgl = it }
        context.importTexture(
          sharedHandle,
          extent,
          "Direct3D 12",
          WindowsDirect3DInterop.adapterLuidOf(device),
        )
      } else {
        val context =
          vulkan ?: VulkanDevice.forDirect3D12Resource(sharedHandle).also { vulkan = it }
        context.importDirect3D12Resource(sharedHandle, extent)
      }
    } finally {
      // The import duplicates the handle rather than taking ownership, so this copy is always ours.
      WindowsDirect3DInterop.closeSharedHandle(sharedHandle)
    }
  }

  private fun presentationTarget(texture: NativeHandle, extent: MapExtent, generation: Long) =
    Direct3DTextureTarget(
      texture = texture,
      format = dxgiFormat,
      colorFormat =
        if (producer == MapRenderBackend.OpenGl) SurfaceColorFormat.RGBA_8888
        else SurfaceColorFormat.BGRA_8888,
      origin =
        if (producer == MapRenderBackend.OpenGl) TextureOrigin.BottomLeft
        else TextureOrigin.TopLeft,
      extent = extent,
      generation = generation,
    )

  override fun waitForProducers() {
    vulkan?.waitIdle()
    wgl?.waitIdle()
  }

  override fun present(
    scope: DrawScope,
    context: Direct3D12ComposeGpuContext,
    texture: SharedTexture,
    generation: Long,
    destination: MlnFfiMapDestination,
  ): Boolean =
    presenter.draw(scope, context.skiaContext, texture.presentation, destination, frameCompletion)

  /**
   * Never call this from the renderer thread: dropping the Skia wrapper waits on the GPU thread,
   * which is usually the thread blocked on a renderer hop.
   */
  override fun release(texture: SharedTexture) {
    val direct3DTexture = texture.presentation.texture
    // Skia holds a surface wrapping this texture; it must be dropped before the texture is.
    presentationHost.runOnGpuThread { presenter.forget(direct3DTexture.address) }
    WindowsDirect3DInterop.release(direct3DTexture)
  }

  override fun contextReplaced() {
    presenter.closeAll()
  }

  override fun closeTextures() {
    textures.current?.let { rendererThread.run(it::closeImported) }
    // Released on the closing thread, never the renderer thread; see release.
    textures.releaseAll()
    presentationHost.runOnGpuThread(presenter::closeAll)
  }

  override fun closeProducers() {
    val closing = vulkan
    vulkan = null
    closing?.close()
    wgl?.close()
    wgl = null
  }

  /**
   * A Direct3D texture Compose draws, and the producer's view of it. Retiring a texture closes the
   * view at once; the Direct3D texture stays presentable until [release].
   */
  internal class SharedTexture(
    val presentation: Direct3DTextureTarget,
    val device: NativeHandle,
    private val imported: ImportedMapTexture,
  ) {
    private var importedClosed = false

    fun target(generation: Long): MlnFfiRenderTarget {
      check(!importedClosed) { "Windows map texture is not initialized" }
      return imported.target(generation)
    }

    /** Runs on the renderer thread. */
    fun closeImported() {
      if (importedClosed) return
      imported.close()
      importedClosed = true
    }
  }
}

/** A Vulkan device that can import [sharedHandle], an NT handle to a Direct3D 12 resource. */
private fun VulkanDevice.Companion.forDirect3D12Resource(sharedHandle: Long): VulkanDevice =
  create(setOf(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME)) { _, device ->
    MemoryStack.stackPush().use { stack ->
      val properties =
        VkMemoryWin32HandlePropertiesKHR.calloc(stack)
          .sType(VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR)
      vkGetMemoryWin32HandlePropertiesKHR(
        device,
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT,
        sharedHandle,
        properties,
      ) == VK_SUCCESS && properties.memoryTypeBits() != 0
    }
  }

/** A `VkImage` bound to the Direct3D 12 resource [sharedHandle] names, allocated at [extent]. */
private fun VulkanDevice.importDirect3D12Resource(sharedHandle: Long, extent: MapExtent) =
  VulkanImage.create(
    this,
    extent,
    VK_FORMAT_B8G8R8A8_UNORM,
    VK_IMAGE_LAYOUT_GENERAL,
    VulkanImageMemory.ImportedWin32(
      VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT,
      sharedHandle,
      "D3D12 resource",
    ),
  )

/**
 * The slice of Direct3D 12 this host needs, called through the JDK's foreign function API.
 *
 * The vtable indices below come from declaration order in `d3d12.h`; they are unchecked, and a
 * wrong index calls the wrong method rather than failing.
 */
private object WindowsDirect3DInterop {
  private const val ID3d12DeviceIidData1 = 0x189819F1
  private const val ID3d12DeviceIidData2 = 0x1DB6
  private const val ID3d12DeviceIidData3 = 0x4B57
  private const val ID3d12ResourceIidData1 = 0x696442BE
  private const val ID3d12ResourceIidData2 = 0xA72E
  private const val ID3d12ResourceIidData3 = 0x4059
  private const val GENERIC_ALL = 0x10000000

  private const val D3D12_HEAP_TYPE_DEFAULT = 1
  private const val D3D12_HEAP_FLAG_SHARED = 0x1
  private const val D3D12_RESOURCE_DIMENSION_TEXTURE2D = 3
  private const val D3D12_RESOURCE_STATE_COMMON = 0
  private const val D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET = 0x1
  private const val D3D12_TEXTURE_LAYOUT_UNKNOWN = 0
  private const val ID3d12DeviceChildGetDeviceIndex = 7
  private const val ID3d12DeviceCreateCommittedResourceIndex = 27
  private const val ID3d12DeviceCreateSharedHandleIndex = 31
  private const val ID3d12DeviceGetAdapterLuidIndex = 43
  private const val IUnknownReleaseIndex = 2

  private val linker = Linker.nativeLinker()
  private val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
  private val closeHandle =
    linker.downcallHandle(
      kernel32.findOrThrow("CloseHandle"),
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )

  fun adapterLuidOf(device: NativeHandle): Long =
    Arena.ofConfined().use { arena ->
      val luid = arena.allocate(ValueLayout.JAVA_LONG)
      // The Windows C ABI returns LUID through an explicit output pointer (d3d12.h).
      linker
        .downcallHandle(
          comMethod(device.address, ID3d12DeviceGetAdapterLuidIndex),
          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
        .invokeWithArguments(address(device.address), luid)
      luid.get(ValueLayout.JAVA_LONG, 0)
    }

  /**
   * Allocates an `ID3D12Resource` texture on Compose's device, shareable via [createSharedHandle].
   */
  fun createSharedTexture(
    device: NativeHandle,
    extent: MapExtent,
    dxgiFormat: Int = DXGI_FORMAT_B8G8R8A8_UNORM,
  ): NativeHandle {
    check(!extent.isEmpty) { "Cannot create a D3D12 texture for an empty extent" }
    Arena.ofConfined().use { arena ->
      val rawDevice = device.address
      val resourceOut = arena.allocate(ValueLayout.ADDRESS)
      checkHResult(
        invokeHResult(
          comMethod(rawDevice, ID3d12DeviceCreateCommittedResourceIndex),
          address(rawDevice),
          heapProperties(arena),
          D3D12_HEAP_FLAG_SHARED,
          textureDesc(arena, extent, dxgiFormat),
          D3D12_RESOURCE_STATE_COMMON,
          MemorySegment.NULL,
          iidId3D12Resource(arena),
          resourceOut,
        ),
        "ID3D12Device::CreateCommittedResource",
      )
      val resource = resourceOut.get(ValueLayout.ADDRESS, 0).address()
      check(resource != NULL) { "ID3D12Device::CreateCommittedResource returned null" }
      return NativeHandle(resource)
    }
  }

  /**
   * Opens an NT handle naming [resource], which Vulkan can import. The handle belongs to the
   * caller, who must close it once the import has duplicated it.
   */
  fun createSharedHandle(resource: NativeHandle): Long {
    check(resource.address != 0L) { "Cannot share a null D3D12 resource" }
    Arena.ofConfined().use { arena ->
      val deviceOut = arena.allocate(ValueLayout.ADDRESS)
      checkHResult(
        invokeHResult(
          comMethod(resource.address, ID3d12DeviceChildGetDeviceIndex),
          address(resource.address),
          iidId3D12Device(arena),
          deviceOut,
        ),
        "ID3D12Resource::GetDevice",
      )
      val device = deviceOut.get(ValueLayout.ADDRESS, 0).address()
      try {
        val handleOut = arena.allocate(ValueLayout.ADDRESS)
        checkHResult(
          invokeHResult(
            comMethod(device, ID3d12DeviceCreateSharedHandleIndex),
            address(device),
            address(resource.address),
            MemorySegment.NULL,
            GENERIC_ALL,
            MemorySegment.NULL,
            handleOut,
          ),
          "ID3D12Device::CreateSharedHandle",
        )
        val handle = handleOut.get(ValueLayout.ADDRESS, 0).address()
        check(handle != NULL) { "ID3D12Device::CreateSharedHandle returned a null handle" }
        return handle
      } finally {
        // GetDevice addrefs its result, so this releases our reference, not Compose's device.
        release(device)
      }
    }
  }

  fun release(resource: NativeHandle) {
    release(resource.address)
  }

  fun closeSharedHandle(handle: Long) {
    if (handle != NULL) {
      closeHandle.invokeWithArguments(address(handle))
    }
  }

  /** `D3D12_HEAP_PROPERTIES` for a default (device-local) heap on the single-adapter node. */
  private fun heapProperties(arena: Arena): MemorySegment {
    val props = arena.allocate(20)
    props.set(ValueLayout.JAVA_INT, 0, D3D12_HEAP_TYPE_DEFAULT)
    props.set(ValueLayout.JAVA_INT, 4, 0)
    props.set(ValueLayout.JAVA_INT, 8, 0)
    props.set(ValueLayout.JAVA_INT, 12, 1)
    props.set(ValueLayout.JAVA_INT, 16, 1)
    return props
  }

  /** `D3D12_RESOURCE_DESC` for a single-sampled, single-mip 2D texture. */
  private fun textureDesc(arena: Arena, extent: MapExtent, dxgiFormat: Int): MemorySegment {
    val desc = arena.allocate(56)
    desc.set(ValueLayout.JAVA_INT, 0, D3D12_RESOURCE_DIMENSION_TEXTURE2D)
    desc.set(ValueLayout.JAVA_LONG, 8, 0)
    desc.set(ValueLayout.JAVA_LONG, 16, extent.physicalWidth.toLong())
    desc.set(ValueLayout.JAVA_INT, 24, extent.physicalHeight)
    desc.set(ValueLayout.JAVA_SHORT, 28, 1.toShort())
    desc.set(ValueLayout.JAVA_SHORT, 30, 1.toShort())
    desc.set(ValueLayout.JAVA_INT, 32, dxgiFormat)
    desc.set(ValueLayout.JAVA_INT, 36, 1)
    desc.set(ValueLayout.JAVA_INT, 40, 0)
    desc.set(ValueLayout.JAVA_INT, 44, D3D12_TEXTURE_LAYOUT_UNKNOWN)
    desc.set(ValueLayout.JAVA_INT, 48, D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET)
    return desc
  }

  /** `IID_ID3D12Device`, `{189819F1-1DB6-4B57-BE54-1821339B85F7}`. */
  private fun iidId3D12Device(arena: Arena): MemorySegment =
    guid(
      arena,
      ID3d12DeviceIidData1,
      ID3d12DeviceIidData2,
      ID3d12DeviceIidData3,
      0xBE,
      0x54,
      0x18,
      0x21,
      0x33,
      0x9B,
      0x85,
      0xF7,
    )

  /** `IID_ID3D12Resource`, `{696442BE-A72E-4059-BC79-5B5C98040FAD}`. */
  private fun iidId3D12Resource(arena: Arena): MemorySegment =
    guid(
      arena,
      ID3d12ResourceIidData1,
      ID3d12ResourceIidData2,
      ID3d12ResourceIidData3,
      0xBC,
      0x79,
      0x5B,
      0x5C,
      0x98,
      0x04,
      0x0F,
      0xAD,
    )

  private fun guid(
    arena: Arena,
    data1: Int,
    data2: Int,
    data3: Int,
    vararg data4: Int,
  ): MemorySegment {
    val iid = arena.allocate(16)
    iid.set(ValueLayout.JAVA_INT, 0, data1)
    iid.set(ValueLayout.JAVA_SHORT, 4, data2.toShort())
    iid.set(ValueLayout.JAVA_SHORT, 6, data3.toShort())
    data4.forEachIndexed { index, value ->
      iid.set(ValueLayout.JAVA_BYTE, 8L + index, value.toByte())
    }
    return iid
  }

  /** The [index]th entry of [instance]'s COM vtable. */
  private fun comMethod(instance: Long, index: Int): MemorySegment {
    val vtable = address(instance).reinterpret(Long.SIZE_BYTES.toLong()).get(ValueLayout.ADDRESS, 0)
    return vtable
      .reinterpret((index + 1L) * Long.SIZE_BYTES)
      .get(ValueLayout.ADDRESS, index * Long.SIZE_BYTES.toLong())
  }

  private fun release(instance: Long) {
    if (instance != NULL) {
      invokeInt(
        comMethod(instance, IUnknownReleaseIndex),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        address(instance),
      )
    }
  }

  private fun invokeHResult(function: MemorySegment, vararg args: Any): Int =
    invokeInt(function, hresultDescriptor(args.size), *args)

  private fun invokeInt(
    function: MemorySegment,
    descriptor: FunctionDescriptor,
    vararg args: Any,
  ): Int = linker.downcallHandle(function, descriptor).invokeWithArguments(*args) as Int

  /**
   * The descriptor for an `HRESULT`-returning COM method of [argumentCount] arguments. A wrong
   * branch is a stack mismatch, not an exception.
   */
  private fun hresultDescriptor(argumentCount: Int): FunctionDescriptor =
    when (argumentCount) {
      // ID3D12DeviceChild::GetDevice(this, riid, ppvDevice)
      3 ->
        FunctionDescriptor.of(
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
        )
      // ID3D12Device::CreateSharedHandle(this, pObject, pAttributes, Access, Name, pHandle)
      6 ->
        FunctionDescriptor.of(
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
        )
      // ID3D12Device::CreateCommittedResource(this, pHeapProperties, HeapFlags, pDesc,
      // InitialResourceState, pOptimizedClearValue, riidResource, ppvResource)
      8 ->
        FunctionDescriptor.of(
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
          ValueLayout.ADDRESS,
        )
      else -> error("Unsupported HRESULT function arity: $argumentCount")
    }

  private fun address(value: Long): MemorySegment = MemorySegment.ofAddress(value)

  private fun checkHResult(hr: Int, operation: String) {
    check(hr >= 0) { "$operation failed with HRESULT 0x${hr.toUInt().toString(16)}" }
  }
}
