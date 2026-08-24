package org.maplibre.compose.desktop.bridge

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import org.lwjgl.system.MemoryUtil.NULL
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.NativeHandle

/**
 * The slice of Direct3D 12 this host needs, called through the JDK's foreign function API.
 *
 * The vtable indices below come from declaration order in `d3d12.h`; they are unchecked, and a
 * wrong index calls the wrong method rather than failing.
 */
internal object WindowsDirect3DInterop {
  private const val IID_ID3D12_DEVICE_DATA1 = 0x189819F1
  private const val IID_ID3D12_DEVICE_DATA2 = 0x1DB6
  private const val IID_ID3D12_DEVICE_DATA3 = 0x4B57
  private const val IID_ID3D12_RESOURCE_DATA1 = 0x696442BE
  private const val IID_ID3D12_RESOURCE_DATA2 = 0xA72E
  private const val IID_ID3D12_RESOURCE_DATA3 = 0x4059
  private const val GENERIC_ALL = 0x10000000

  private const val D3D12_HEAP_TYPE_DEFAULT = 1
  private const val D3D12_HEAP_FLAG_SHARED = 0x1
  private const val D3D12_RESOURCE_DIMENSION_TEXTURE2D = 3
  private const val D3D12_RESOURCE_STATE_COMMON = 0
  private const val D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET = 0x1
  private const val D3D12_TEXTURE_LAYOUT_UNKNOWN = 0
  private const val ID3D12_DEVICE_CHILD_GET_DEVICE_INDEX = 7
  private const val ID3D12_DEVICE_CREATE_COMMITTED_RESOURCE_INDEX = 27
  private const val ID3D12_DEVICE_CREATE_SHARED_HANDLE_INDEX = 31
  private const val IUNKNOWN_RELEASE_INDEX = 2

  private val linker = Linker.nativeLinker()
  private val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
  private val closeHandle =
    linker.downcallHandle(
      kernel32.findOrThrow("CloseHandle"),
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )

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
          comMethod(rawDevice, ID3D12_DEVICE_CREATE_COMMITTED_RESOURCE_INDEX),
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
          comMethod(resource.address, ID3D12_DEVICE_CHILD_GET_DEVICE_INDEX),
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
            comMethod(device, ID3D12_DEVICE_CREATE_SHARED_HANDLE_INDEX),
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
      IID_ID3D12_DEVICE_DATA1,
      IID_ID3D12_DEVICE_DATA2,
      IID_ID3D12_DEVICE_DATA3,
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
      IID_ID3D12_RESOURCE_DATA1,
      IID_ID3D12_RESOURCE_DATA2,
      IID_ID3D12_RESOURCE_DATA3,
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
        comMethod(instance, IUNKNOWN_RELEASE_INDEX),
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
