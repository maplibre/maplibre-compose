package org.maplibre.compose.mlnffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.util.UUID
import org.jetbrains.skia.DirectContext

/**
 * A software Direct3D 12 consumer for Windows bridge tests, without a window or swap chain.
 *
 * The test worker owns its shared context. Initialization failures release the acquired COM
 * objects; successful initialization leaves them alive until the worker exits, like its Skia
 * context.
 */
internal class WindowsOffscreenTestContext
private constructor(
  private val adapter: MemorySegment,
  val device: MemorySegment,
  private val queue: MemorySegment,
  val context: DirectContext,
) : AutoCloseable {
  override fun close() {
    context.close()
    release(queue)
    release(device)
    release(adapter)
  }

  companion object {
    private const val FactoryEnumWarpAdapterIndex = 27
    private const val DeviceCreateCommandQueueIndex = 8
    private const val UnknownReleaseIndex = 2
    private const val FeatureLevel11 = 0xb000
    private val PointerSize = ValueLayout.ADDRESS.byteSize()
    private val linker = Linker.nativeLinker()

    fun create(): WindowsOffscreenTestContext =
      Arena.ofConfined().use { arena ->
        val dxgi = SymbolLookup.libraryLookup("dxgi", Arena.global())
        val d3d12 = SymbolLookup.libraryLookup("d3d12", Arena.global())
        val factoryOut = arena.allocate(ValueLayout.ADDRESS)
        checkResult(
          invoke(
            dxgi.findOrThrow("CreateDXGIFactory1"),
            guid(arena, "1bc6ea02-ef36-464f-bf0c-21ca39e5168a"), // IDXGIFactory4
            factoryOut,
          ),
          "CreateDXGIFactory1",
        )
        val factory = factoryOut.get(ValueLayout.ADDRESS, 0)
        val adapter =
          try {
            val out = arena.allocate(ValueLayout.ADDRESS)
            checkResult(
              invoke(
                method(factory, FactoryEnumWarpAdapterIndex),
                factory,
                guid(arena, "29038f61-3839-4626-91fd-086879011a05"), // IDXGIAdapter1
                out,
              ),
              "EnumWarpAdapter",
            )
            out.get(ValueLayout.ADDRESS, 0)
          } finally {
            release(factory)
          }
        try {
          val deviceOut = arena.allocate(ValueLayout.ADDRESS)
          val createDevice =
            linker.downcallHandle(
              d3d12.findOrThrow("D3D12CreateDevice"),
              FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
              ),
            )
          checkResult(
            createDevice.invokeWithArguments(
              adapter,
              FeatureLevel11,
              guid(arena, "189819f1-1db6-4b57-be54-1821339b85f7"), // ID3D12Device
              deviceOut,
            ) as Int,
            "D3D12CreateDevice(WARP)",
          )
          val device = deviceOut.get(ValueLayout.ADDRESS, 0)
          try {
            val queueOut = arena.allocate(ValueLayout.ADDRESS)
            checkResult(
              invoke(
                method(device, DeviceCreateCommandQueueIndex),
                device,
                arena.allocate(16), // D3D12_COMMAND_QUEUE_DESC: DIRECT, normal priority, no flags.
                guid(arena, "0ec870a6-5d7e-4c22-8cfc-5baae07616ed"), // ID3D12CommandQueue
                queueOut,
              ),
              "ID3D12Device.CreateCommandQueue",
            )
            val queue = queueOut.get(ValueLayout.ADDRESS, 0)
            try {
              WindowsOffscreenTestContext(
                adapter,
                device,
                queue,
                DirectContext.makeDirect3D(adapter.address(), device.address(), queue.address()),
              )
            } catch (error: Throwable) {
              release(queue)
              throw error
            }
          } catch (error: Throwable) {
            release(device)
            throw error
          }
        } catch (error: Throwable) {
          release(adapter)
          throw error
        }
      }

    private fun method(instance: MemorySegment, index: Int): MemorySegment {
      val vtable = instance.reinterpret(PointerSize).get(ValueLayout.ADDRESS, 0)
      return vtable
        .reinterpret((index + 1L) * PointerSize)
        .get(ValueLayout.ADDRESS, index * PointerSize)
    }

    private fun invoke(function: MemorySegment, vararg args: MemorySegment): Int =
      linker
        .downcallHandle(
          function,
          FunctionDescriptor.of(ValueLayout.JAVA_INT, *Array(args.size) { ValueLayout.ADDRESS }),
        )
        .invokeWithArguments(*args) as Int

    private fun release(instance: MemorySegment) {
      invoke(method(instance, UnknownReleaseIndex), instance)
    }

    private fun guid(arena: Arena, text: String): MemorySegment {
      val uuid = UUID.fromString(text)
      val result = arena.allocate(16)
      result.set(ValueLayout.JAVA_INT, 0, (uuid.mostSignificantBits ushr 32).toInt())
      result.set(ValueLayout.JAVA_SHORT, 4, (uuid.mostSignificantBits ushr 16).toShort())
      result.set(ValueLayout.JAVA_SHORT, 6, uuid.mostSignificantBits.toShort())
      repeat(8) { index ->
        result.set(
          ValueLayout.JAVA_BYTE,
          8L + index,
          (uuid.leastSignificantBits ushr (56 - index * 8)).toByte(),
        )
      }
      return result
    }

    private fun checkResult(result: Int, operation: String) {
      check(result >= 0) { "$operation failed with HRESULT 0x${result.toUInt().toString(16)}" }
    }
  }
}
