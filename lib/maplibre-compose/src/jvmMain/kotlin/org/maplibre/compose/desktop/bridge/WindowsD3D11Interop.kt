package org.maplibre.compose.desktop.bridge

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import org.lwjgl.system.MemoryUtil.NULL
import org.maplibre.compose.map.MapExtent

/** A D3D11 texture on ANGLE's device, exported as an NT handle for Vulkan. */
internal class WindowsD3D11SharedTexture(
  val sharedHandle: Long,
  val texture: Long,
) : AutoCloseable {
  override fun close() {
    WindowsD3D11Interop.release(texture)
    WindowsD3D11Interop.closeSharedHandle(sharedHandle)
  }
}

/** The D3D11/DXGI calls this host needs, via the JDK foreign-function API. */
internal object WindowsD3D11Interop {
  private const val DXGI_FORMAT_R8G8B8A8_UNORM = 28
  private const val D3D11_BIND_SHADER_RESOURCE = 0x8
  private const val D3D11_BIND_RENDER_TARGET = 0x20
  private const val D3D11_RESOURCE_MISC_SHARED = 0x2
  private const val D3D11_RESOURCE_MISC_SHARED_NTHANDLE = 0x800
  private const val GENERIC_ALL = 0x10000000
  private const val IUNKNOWN_QUERY_INTERFACE_INDEX = 0
  private const val IUNKNOWN_RELEASE_INDEX = 2
  private const val ID3D11_DEVICE_CREATE_TEXTURE_2D_INDEX = 5
  private const val IDXGI_DEVICE_GET_ADAPTER_INDEX = 7
  private const val IDXGI_ADAPTER_GET_DESC_INDEX = 8
  private const val IDXGI_RESOURCE1_CREATE_SHARED_HANDLE_INDEX = 13
  private const val DXGI_ADAPTER_DESC_LUID_OFFSET = 296L
  private const val DXGI_ADAPTER_DESC_SIZE = 304L

  private val linker = Linker.nativeLinker()
  private val closeHandle =
    linker.downcallHandle(
      SymbolLookup.libraryLookup("kernel32", Arena.global()).findOrThrow("CloseHandle"),
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )

  /**
   * Allocates on [device] so ANGLE can wrap the same `ID3D11Texture2D`. Does not take ownership of
   * [device].
   */
  fun createSharedTextureOnDevice(device: Long, extent: MapExtent): WindowsD3D11SharedTexture {
    check(!extent.isEmpty) { "Cannot create a D3D11 texture for an empty extent" }
    Arena.ofConfined().use { arena ->
      val texture = createTexture(arena, device, extent)
      try {
        return WindowsD3D11SharedTexture(createSharedHandle(arena, texture), texture)
      } catch (error: RuntimeException) {
        release(texture)
        throw error
      }
    }
  }

  fun adapterLuidOf(device: Long): Long {
    Arena.ofConfined().use { arena ->
      val dxgiOut = arena.allocate(ValueLayout.ADDRESS)
      checkHResult(
        invokeHResult(
          comMethod(device, IUNKNOWN_QUERY_INTERFACE_INDEX),
          address(device),
          iid(arena, 0x54ec77fa, 0x1377, 0x44e6, 0x8c, 0x32, 0x88, 0xfd, 0x5f, 0x44, 0xc8, 0x4c),
          dxgiOut,
        ),
        "ID3D11Device::QueryInterface(IDXGIDevice)",
      )
      val dxgi = dxgiOut.get(ValueLayout.ADDRESS, 0).address()
      try {
        val adapterOut = arena.allocate(ValueLayout.ADDRESS)
        checkHResult(
          invokeInt(
            comMethod(dxgi, IDXGI_DEVICE_GET_ADAPTER_INDEX),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            address(dxgi),
            adapterOut,
          ),
          "IDXGIDevice::GetAdapter",
        )
        val adapter = adapterOut.get(ValueLayout.ADDRESS, 0).address()
        try {
          val desc = arena.allocate(DXGI_ADAPTER_DESC_SIZE)
          checkHResult(
            invokeInt(
              comMethod(adapter, IDXGI_ADAPTER_GET_DESC_INDEX),
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
              address(adapter),
              desc,
            ),
            "IDXGIAdapter::GetDesc",
          )
          val luid = desc.get(ValueLayout.JAVA_LONG, DXGI_ADAPTER_DESC_LUID_OFFSET)
          check(luid != 0L) { "IDXGIAdapter::GetDesc returned a zero LUID" }
          return luid
        } finally {
          release(adapter)
        }
      } finally {
        release(dxgi)
      }
    }
  }

  fun closeSharedHandle(handle: Long) {
    if (handle != NULL) closeHandle.invokeWithArguments(address(handle))
  }

  fun release(instance: Long) {
    if (instance != NULL) {
      invokeInt(
        comMethod(instance, IUNKNOWN_RELEASE_INDEX),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        address(instance),
      )
    }
  }

  private fun createTexture(arena: Arena, device: Long, extent: MapExtent): Long {
    val desc = arena.allocate(44)
    desc.set(ValueLayout.JAVA_INT, 0, extent.physicalWidth)
    desc.set(ValueLayout.JAVA_INT, 4, extent.physicalHeight)
    desc.set(ValueLayout.JAVA_INT, 8, 1)
    desc.set(ValueLayout.JAVA_INT, 12, 1)
    desc.set(ValueLayout.JAVA_INT, 16, DXGI_FORMAT_R8G8B8A8_UNORM)
    desc.set(ValueLayout.JAVA_INT, 20, 1)
    desc.set(ValueLayout.JAVA_INT, 24, 0)
    desc.set(ValueLayout.JAVA_INT, 28, 0)
    desc.set(ValueLayout.JAVA_INT, 32, D3D11_BIND_RENDER_TARGET or D3D11_BIND_SHADER_RESOURCE)
    desc.set(ValueLayout.JAVA_INT, 36, 0)
    desc.set(
      ValueLayout.JAVA_INT,
      40,
      D3D11_RESOURCE_MISC_SHARED or D3D11_RESOURCE_MISC_SHARED_NTHANDLE,
    )
    val textureOut = arena.allocate(ValueLayout.ADDRESS)
    checkHResult(
      invokeHResult(
        comMethod(device, ID3D11_DEVICE_CREATE_TEXTURE_2D_INDEX),
        address(device),
        desc,
        MemorySegment.NULL,
        textureOut,
      ),
      "ID3D11Device::CreateTexture2D",
    )
    val texture = textureOut.get(ValueLayout.ADDRESS, 0).address()
    check(texture != NULL) { "ID3D11Device::CreateTexture2D returned null" }
    return texture
  }

  private fun createSharedHandle(arena: Arena, texture: Long): Long {
    val resourceOut = arena.allocate(ValueLayout.ADDRESS)
    checkHResult(
      invokeHResult(
        comMethod(texture, IUNKNOWN_QUERY_INTERFACE_INDEX),
        address(texture),
        iid(arena, 0x30961379, 0x4609, 0x4a41, 0x99, 0x8e, 0x54, 0xfe, 0x56, 0x7e, 0xe0, 0xc1),
        resourceOut,
      ),
      "ID3D11Texture2D::QueryInterface(IDXGIResource1)",
    )
    val resource = resourceOut.get(ValueLayout.ADDRESS, 0).address()
    try {
      val handleOut = arena.allocate(ValueLayout.ADDRESS)
      checkHResult(
        invokeInt(
          comMethod(resource, IDXGI_RESOURCE1_CREATE_SHARED_HANDLE_INDEX),
          FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
          ),
          address(resource),
          MemorySegment.NULL,
          GENERIC_ALL,
          MemorySegment.NULL,
          handleOut,
        ),
        "IDXGIResource1::CreateSharedHandle",
      )
      val handle = handleOut.get(ValueLayout.ADDRESS, 0).address()
      check(handle != NULL) { "IDXGIResource1::CreateSharedHandle returned a null handle" }
      return handle
    } finally {
      release(resource)
    }
  }

  private fun iid(
    arena: Arena,
    data1: Int,
    data2: Int,
    data3: Int,
    vararg data4: Int,
  ): MemorySegment {
    val guid = arena.allocate(16)
    guid.set(ValueLayout.JAVA_INT, 0, data1)
    guid.set(ValueLayout.JAVA_SHORT, 4, data2.toShort())
    guid.set(ValueLayout.JAVA_SHORT, 6, data3.toShort())
    data4.forEachIndexed { index, value ->
      guid.set(ValueLayout.JAVA_BYTE, 8L + index, value.toByte())
    }
    return guid
  }

  private fun comMethod(instance: Long, index: Int): MemorySegment {
    val vtable = address(instance).reinterpret(Long.SIZE_BYTES.toLong()).get(ValueLayout.ADDRESS, 0)
    return vtable
      .reinterpret((index + 1L) * Long.SIZE_BYTES)
      .get(ValueLayout.ADDRESS, index * Long.SIZE_BYTES.toLong())
  }

  private fun invokeHResult(function: MemorySegment, vararg args: Any): Int =
    invokeInt(
      function,
      when (args.size) {
        3 ->
          FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
          )
        4 ->
          FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
          )
        else -> error("Unsupported HRESULT arity: ${args.size}")
      },
      *args,
    )

  private fun invokeInt(
    function: MemorySegment,
    descriptor: FunctionDescriptor,
    vararg args: Any,
  ): Int = linker.downcallHandle(function, descriptor).invokeWithArguments(*args) as Int

  private fun address(value: Long): MemorySegment = MemorySegment.ofAddress(value)

  private fun checkHResult(hr: Int, operation: String) {
    check(hr >= 0) { "$operation failed with HRESULT 0x${hr.toUInt().toString(16)}" }
  }
}
