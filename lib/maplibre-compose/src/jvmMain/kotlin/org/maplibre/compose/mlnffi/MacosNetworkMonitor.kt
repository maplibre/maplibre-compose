package org.maplibre.compose.mlnffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.CountDownLatch
import org.maplibre.compose.logging.MapLog
import org.maplibre.nativeffi.runtime.NetworkStatus

/** Network.framework's path monitor, using the same C API as the Kotlin/Native Apple targets. */
internal class MacosNetworkMonitor(private val onStatus: (NetworkStatus) -> Unit) : AutoCloseable {
  private val arena = Arena.ofShared()
  private val cancelled = CountDownLatch(1)
  private val monitor = MacosNetworkFunctions.create.invokeExact() as MemorySegment
  private val queue =
    MacosNetworkFunctions.createQueue.invokeExact(
      arena.allocateFrom("org.maplibre.compose.connectivity"),
      MemorySegment.NULL,
    ) as MemorySegment

  init {
    try {
      check(monitor != MemorySegment.NULL) { "Could not create the network path monitor" }
      check(queue != MemorySegment.NULL) { "Could not create the network path monitor queue" }
      MacosNetworkFunctions.setQueue.invokeExact(monitor, queue)
      MacosNetworkFunctions.setUpdateHandler.invokeExact(monitor, block("pathChanged", 2))
      MacosNetworkFunctions.setCancelHandler.invokeExact(monitor, block("didCancel", 1))
      MacosNetworkFunctions.start.invokeExact(monitor)
    } catch (error: Throwable) {
      if (monitor != MemorySegment.NULL) MacosNetworkFunctions.release.invokeExact(monitor)
      if (queue != MemorySegment.NULL) MacosNetworkFunctions.releaseQueue.invokeExact(queue)
      arena.close()
      throw error
    }
  }

  override fun close() {
    MacosNetworkFunctions.cancel.invokeExact(monitor)
    // The cancel handler guarantees that no more path updates will arrive. Drain its queue too,
    // so the cancel upcall has returned before releasing the arena containing its function stub.
    var interrupted = false
    while (true) {
      try {
        cancelled.await()
        break
      } catch (_: InterruptedException) {
        interrupted = true
      }
    }
    MacosNetworkFunctions.syncQueue.invokeExact(
      queue,
      MemorySegment.NULL,
      MacosNetworkFunctions.noop,
    )
    MacosNetworkFunctions.release.invokeExact(monitor)
    MacosNetworkFunctions.releaseQueue.invokeExact(queue)
    arena.close()
    if (interrupted) Thread.currentThread().interrupt()
  }

  @Suppress("UNUSED_PARAMETER")
  private fun pathChanged(block: MemorySegment, path: MemorySegment) {
    // Never let a JVM exception unwind through a native callback.
    runCatching {
      val status = MacosNetworkFunctions.pathStatus.invokeExact(path) as Int
      onStatus(if (status == 2) NetworkStatus.OFFLINE else NetworkStatus.ONLINE)
    }
      .onFailure { error ->
        runCatching { MapLog.w(error) { "Could not report network connectivity" } }
      }
  }

  @Suppress("UNUSED_PARAMETER")
  private fun didCancel(block: MemorySegment) {
    cancelled.countDown()
  }

  /**
   * A block literal with no captured native fields, following the Apple Blocks ABI. Network
   * framework borrows its storage; it stays alive until cancellation and monitor release.
   * https://clang.llvm.org/docs/Block-ABI-Apple.html
   */
  private fun block(method: String, parameters: Int): MemorySegment {
    val arguments = Array(parameters) { ADDRESS }
    val descriptor = FunctionDescriptor.ofVoid(*arguments)
    val target =
      MethodHandles.lookup()
        .findVirtual(
          MacosNetworkMonitor::class.java,
          method,
          MethodType.methodType(Void.TYPE, List(parameters) { MemorySegment::class.java }),
        )
        .bindTo(this)
    val invoke = MacosNetworkFunctions.linker.upcallStub(target, descriptor, arena)
    val blockDescriptor = arena.allocate(24, 8)
    blockDescriptor.set(JAVA_LONG, 8, 32)
    blockDescriptor.set(
      ADDRESS,
      16,
      arena.allocateFrom(if (parameters == 2) "v16@?0^v8" else "v8@?0"),
    )
    return arena.allocate(32, 8).also {
      it.set(ADDRESS, 0, MacosNetworkFunctions.globalBlockClass)
      it.set(JAVA_INT, 8, (1 shl 28) or (1 shl 30)) // GLOBAL | HAS_SIGNATURE
      it.set(ADDRESS, 16, invoke)
      it.set(ADDRESS, 24, blockDescriptor)
    }
  }
}

private object MacosNetworkFunctions {
  val linker: Linker = Linker.nativeLinker()
  private val network =
    SymbolLookup.libraryLookup(
      "/System/Library/Frameworks/Network.framework/Network",
      Arena.global(),
    )
  private val system = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", Arena.global())

  val create = network.function("nw_path_monitor_create", FunctionDescriptor.of(ADDRESS))
  val setQueue = network.function("nw_path_monitor_set_queue", twoPointers)
  val setUpdateHandler = network.function("nw_path_monitor_set_update_handler", twoPointers)
  val setCancelHandler = network.function("nw_path_monitor_set_cancel_handler", twoPointers)
  val start = network.function("nw_path_monitor_start", onePointer)
  val cancel = network.function("nw_path_monitor_cancel", onePointer)
  val release = network.function("nw_release", onePointer)
  val pathStatus = network.function("nw_path_get_status", FunctionDescriptor.of(JAVA_INT, ADDRESS))
  val createQueue =
    system.function("dispatch_queue_create", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
  val syncQueue =
    system.function("dispatch_sync_f", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS))
  val releaseQueue = system.function("dispatch_release", onePointer)
  val globalBlockClass: MemorySegment = system.find("_NSConcreteGlobalBlock").orElseThrow()
  val noop: MemorySegment =
    linker.upcallStub(
      MethodHandles.lookup()
        .findVirtual(
          MacosNetworkFunctions::class.java,
          "ignore",
          MethodType.methodType(Void.TYPE, MemorySegment::class.java),
        )
        .bindTo(this),
      onePointer,
      Arena.global(),
    )

  @Suppress("UNUSED_PARAMETER") private fun ignore(context: MemorySegment) {}

  private fun SymbolLookup.function(name: String, descriptor: FunctionDescriptor) =
    linker.downcallHandle(find(name).orElseThrow(), descriptor)
}

private val onePointer = FunctionDescriptor.ofVoid(ADDRESS)
private val twoPointers = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
