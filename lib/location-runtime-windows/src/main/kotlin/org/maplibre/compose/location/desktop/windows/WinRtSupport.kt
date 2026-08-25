package org.maplibre.compose.location.desktop.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_CHAR
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class WinRtException(message: String, val hresult: Int) :
  IllegalStateException("$message (HRESULT 0x${hresult.toUInt().toString(16).padStart(8, '0')})")

internal class ComPtr(private var segment: MemorySegment) : AutoCloseable {
  private val closed = AtomicBoolean()

  val value: MemorySegment
    get() {
      check(!closed.get()) { "COM pointer is closed" }
      return segment
    }

  fun queryInterface(iid: String): ComPtr =
    Arena.ofConfined().use { arena ->
      val output = arena.allocate(ADDRESS)
      WinRt.callHresult(
        value,
        0,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
        WinRt.guid(iid, arena),
        output,
      )
      ComPtr(output.get(ADDRESS, 0))
    }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    WinRt.call(
      segment,
      2,
      FunctionDescriptor.of(JAVA_INT, ADDRESS),
    )
    segment = MemorySegment.NULL
  }
}

internal class WinRtHString private constructor(private val value: MemorySegment) : AutoCloseable {
  fun segment(): MemorySegment = value

  override fun close() {
    WinRt.windowsDeleteString.invokeWithArguments(value)
  }

  companion object {
    fun create(text: String): WinRtHString =
      Arena.ofConfined().use { arena ->
        val characters = arena.allocate(JAVA_CHAR, text.length.toLong())
        text.forEachIndexed { index, character ->
          characters.setAtIndex(JAVA_CHAR, index.toLong(), character)
        }
        val output = arena.allocate(ADDRESS)
        WinRt.checkHresult(
          WinRt.windowsCreateString.invokeWithArguments(characters, text.length, output) as Int,
          "WindowsCreateString failed",
        )
        WinRtHString(output.get(ADDRESS, 0))
      }
  }
}

internal object WinRt {
  const val RO_INIT_SINGLETHREADED = 0
  const val RO_INIT_MULTITHREADED = 1
  private const val RPC_E_CHANGED_MODE = -2_147_417_850

  private val linker = Linker.nativeLinker()
  private val lookup: SymbolLookup by lazy {
    System.loadLibrary("combase")
    SymbolLookup.loaderLookup()
  }

  private fun native(name: String, descriptor: FunctionDescriptor) =
    linker.downcallHandle(
      lookup.find(name).orElseThrow { UnsatisfiedLinkError("combase.dll does not export $name") },
      descriptor,
    )

  private val roInitialize by lazy {
    native("RoInitialize", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
  }
  private val roUninitialize by lazy {
    native("RoUninitialize", FunctionDescriptor.ofVoid())
  }
  private val roActivateInstance by lazy {
    native("RoActivateInstance", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
  }
  private val roGetActivationFactory by lazy {
    native(
      "RoGetActivationFactory",
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
    )
  }
  val windowsCreateString by lazy {
    native(
      "WindowsCreateString",
      FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
    )
  }
  val windowsDeleteString by lazy {
    native("WindowsDeleteString", FunctionDescriptor.of(JAVA_INT, ADDRESS))
  }

  fun initialize(mode: Int): Boolean {
    val result = roInitialize.invokeWithArguments(mode) as Int
    if (result == RPC_E_CHANGED_MODE) return false
    checkHresult(result, "RoInitialize failed")
    return true
  }

  fun uninitialize() {
    roUninitialize.invokeWithArguments()
  }

  inline fun <T> inApartment(mode: Int = RO_INIT_MULTITHREADED, block: () -> T): T {
    val ownsInitialization = initialize(mode)
    try {
      return block()
    } finally {
      if (ownsInitialization) uninitialize()
    }
  }

  fun activate(runtimeClass: String): ComPtr =
    WinRtHString.create(runtimeClass).use { className ->
      Arena.ofConfined().use { arena ->
        val output = arena.allocate(ADDRESS)
        checkHresult(
          roActivateInstance.invokeWithArguments(className.segment(), output) as Int,
          "RoActivateInstance($runtimeClass) failed",
        )
        ComPtr(output.get(ADDRESS, 0))
      }
    }

  fun activationFactory(runtimeClass: String, iid: String): ComPtr =
    WinRtHString.create(runtimeClass).use { className ->
      Arena.ofConfined().use { arena ->
        val output = arena.allocate(ADDRESS)
        checkHresult(
          roGetActivationFactory.invokeWithArguments(
            className.segment(),
            guid(iid, arena),
            output,
          ) as Int,
          "RoGetActivationFactory($runtimeClass) failed",
        )
        ComPtr(output.get(ADDRESS, 0))
      }
    }

  fun queryInterface(instance: MemorySegment, iid: String): ComPtr =
    Arena.ofConfined().use { arena ->
      val output = arena.allocate(ADDRESS)
      callHresult(
        instance,
        0,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
        guid(iid, arena),
        output,
      )
      ComPtr(output.get(ADDRESS, 0))
    }

  fun addRef(instance: MemorySegment) {
    call(instance, 1, FunctionDescriptor.of(JAVA_INT, ADDRESS))
  }

  fun call(
    instance: MemorySegment,
    slot: Int,
    descriptor: FunctionDescriptor,
    vararg arguments: Any,
  ): Any? {
    val vtable = instance.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0)
    val function =
      vtable
        .reinterpret((slot + 1L) * ADDRESS.byteSize())
        .get(ADDRESS, slot.toLong() * ADDRESS.byteSize())
    val handle = linker.downcallHandle(function, descriptor)
    return handle.invokeWithArguments(listOf(instance) + arguments)
  }

  fun callHresult(
    instance: MemorySegment,
    slot: Int,
    descriptor: FunctionDescriptor,
    vararg arguments: Any,
  ) {
    checkHresult(call(instance, slot, descriptor, *arguments) as Int, "COM method $slot failed")
  }

  fun intResult(instance: MemorySegment, slot: Int): Int =
    Arena.ofConfined().use { arena ->
      val output = arena.allocate(JAVA_INT)
      callHresult(
        instance,
        slot,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
        output,
      )
      output.get(JAVA_INT, 0)
    }

  fun longResult(instance: MemorySegment, slot: Int): Long =
    Arena.ofConfined().use { arena ->
      val output = arena.allocate(JAVA_LONG)
      callHresult(
        instance,
        slot,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
        output,
      )
      output.get(JAVA_LONG, 0)
    }

  fun doubleResult(instance: MemorySegment, slot: Int): Double =
    Arena.ofConfined().use { arena ->
      val output = arena.allocate(JAVA_DOUBLE)
      callHresult(
        instance,
        slot,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
        output,
      )
      output.get(JAVA_DOUBLE, 0)
    }

  fun pointerResult(instance: MemorySegment, slot: Int): ComPtr =
    Arena.ofConfined().use { arena ->
      val output = arena.allocate(ADDRESS)
      callHresult(
        instance,
        slot,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
        output,
      )
      ComPtr(output.get(ADDRESS, 0))
    }

  fun nullablePointerResult(instance: MemorySegment, slot: Int): ComPtr? =
    Arena.ofConfined().use { arena ->
      val output = arena.allocate(ADDRESS)
      callHresult(
        instance,
        slot,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
        output,
      )
      output.get(ADDRESS, 0).takeUnless { it == MemorySegment.NULL }?.let(::ComPtr)
    }

  fun guid(text: String, arena: Arena): MemorySegment {
    val canonical = text.replace("-", "")
    require(canonical.length == 32) { "Invalid GUID: $text" }
    val bytes =
      ByteArray(16) { index -> canonical.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    val windowsOrder =
      byteArrayOf(
        bytes[3],
        bytes[2],
        bytes[1],
        bytes[0],
        bytes[5],
        bytes[4],
        bytes[7],
        bytes[6],
        bytes[8],
        bytes[9],
        bytes[10],
        bytes[11],
        bytes[12],
        bytes[13],
        bytes[14],
        bytes[15],
      )
    val result = arena.allocate(16)
    windowsOrder.forEachIndexed { index, byte ->
      result.setAtIndex(JAVA_BYTE, index.toLong(), byte)
    }
    return result
  }

  fun guidEquals(segment: MemorySegment, text: String): Boolean =
    Arena.ofConfined().use { arena ->
      val expected = guid(text, arena)
      val actual = segment.reinterpret(16)
      (0L until 16L).all { actual.getAtIndex(JAVA_BYTE, it) == expected.getAtIndex(JAVA_BYTE, it) }
    }

  fun checkHresult(result: Int, message: String) {
    if (result < 0) throw WinRtException(message, result)
  }
}

internal class WinRtEventCallback private constructor(val segment: MemorySegment) : AutoCloseable {
  private val closed = AtomicBoolean()

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    WinRtCallbacks.releaseReference(segment.address())
  }

  companion object {
    fun create(iid: String, invoke: (MemorySegment, MemorySegment) -> Unit): WinRtEventCallback {
      val arena = Arena.ofAuto()
      val segment = arena.allocate(ADDRESS)
      segment.set(ADDRESS, 0, WinRtCallbacks.eventVtable)
      val callback = WinRtEventCallback(segment)
      WinRtCallbacks.put(
        segment.address(),
        WinRtCallbacks.Callback(iid, segment, invoke = invoke),
      )
      return callback
    }
  }
}

internal class WinRtAsyncCallback private constructor(val segment: MemorySegment) : AutoCloseable {
  private val closed = AtomicBoolean()

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    WinRtCallbacks.releaseReference(segment.address())
  }

  companion object {
    fun create(iid: String, invoke: (MemorySegment, Int) -> Unit): WinRtAsyncCallback {
      val arena = Arena.ofAuto()
      val segment = arena.allocate(ADDRESS)
      segment.set(ADDRESS, 0, WinRtCallbacks.asyncVtable)
      val callback = WinRtAsyncCallback(segment)
      WinRtCallbacks.put(
        segment.address(),
        WinRtCallbacks.Callback(iid, segment, asyncInvoke = invoke),
      )
      return callback
    }
  }
}

private object WinRtCallbacks {
  data class Callback(
    val iid: String,
    val segment: MemorySegment,
    val references: AtomicInteger = AtomicInteger(1),
    val invoke: ((MemorySegment, MemorySegment) -> Unit)? = null,
    val asyncInvoke: ((MemorySegment, Int) -> Unit)? = null,
  ) {
    fun addReference(): Int {
      while (true) {
        val current = references.get()
        if (current == 0) return 0
        if (references.compareAndSet(current, current + 1)) return current + 1
      }
    }

    fun releaseReference(): Int {
      while (true) {
        val current = references.get()
        if (current == 0) return 0
        if (references.compareAndSet(current, current - 1)) return current - 1
      }
    }
  }

  private const val S_OK = 0
  private const val E_NOINTERFACE = -2_147_467_262
  private const val E_FAIL = -2_147_467_259
  private const val IID_IUNKNOWN = "00000000-0000-0000-c000-000000000046"
  private const val IID_IAGILE_OBJECT = "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"
  private val callbacks = ConcurrentHashMap<Long, Callback>()
  private val linker = Linker.nativeLinker()
  private val stubs = Arena.global()
  private val lookup = MethodHandles.lookup()

  val eventVtable: MemorySegment by lazy {
    vtable(event = true)
  }
  val asyncVtable: MemorySegment by lazy {
    vtable(event = false)
  }

  fun put(address: Long, callback: Callback) {
    callbacks[address] = callback
  }

  fun releaseReference(address: Long): Int {
    val callback = callbacks[address] ?: return 0
    return callback.releaseReference().also { remaining ->
      if (remaining == 0) callbacks.remove(address, callback)
    }
  }

  private fun vtable(event: Boolean): MemorySegment {
    val table = stubs.allocate(ADDRESS, 4)
    table.setAtIndex(ADDRESS, 0, stub("queryInterface", QUERY_INTERFACE))
    table.setAtIndex(ADDRESS, 1, stub("addRef", ADD_REF))
    table.setAtIndex(ADDRESS, 2, stub("release", ADD_REF))
    table.setAtIndex(
      ADDRESS,
      3,
      if (event) stub("invokeEvent", INVOKE_EVENT) else stub("invokeAsync", INVOKE_ASYNC),
    )
    return table
  }

  private fun stub(name: String, descriptor: FunctionDescriptor): MemorySegment {
    val parameterTypes =
      descriptor.argumentLayouts().map {
        when (it) {
          JAVA_INT -> Int::class.javaPrimitiveType
          else -> MemorySegment::class.java
        }
      }
    val returnType =
      when (descriptor.returnLayout().orElse(null)) {
        JAVA_INT -> Int::class.javaPrimitiveType
        else -> Void.TYPE
      }
    val handle =
      lookup.findStatic(javaClass, name, MethodType.methodType(returnType, parameterTypes))
    return linker.upcallStub(handle, descriptor, stubs)
  }

  @JvmStatic
  private fun queryInterface(
    self: MemorySegment,
    iid: MemorySegment,
    output: MemorySegment,
  ): Int {
    val callback = callbacks[self.address()] ?: return E_NOINTERFACE
    return if (
      WinRt.guidEquals(iid, IID_IUNKNOWN) ||
        WinRt.guidEquals(iid, IID_IAGILE_OBJECT) ||
        WinRt.guidEquals(iid, callback.iid)
    ) {
      if (callback.addReference() == 0) {
        output.reinterpret(ADDRESS.byteSize()).set(ADDRESS, 0, MemorySegment.NULL)
        E_NOINTERFACE
      } else {
        output.reinterpret(ADDRESS.byteSize()).set(ADDRESS, 0, self)
        S_OK
      }
    } else {
      output.reinterpret(ADDRESS.byteSize()).set(ADDRESS, 0, MemorySegment.NULL)
      E_NOINTERFACE
    }
  }

  @JvmStatic
  private fun addRef(self: MemorySegment): Int = callbacks[self.address()]?.addReference() ?: 0

  @JvmStatic private fun release(self: MemorySegment): Int = releaseReference(self.address())

  @JvmStatic
  private fun invokeEvent(
    self: MemorySegment,
    sender: MemorySegment,
    arguments: MemorySegment,
  ): Int =
    try {
      callbacks[self.address()]?.invoke?.invoke(sender, arguments) ?: return E_FAIL
      S_OK
    } catch (_: Throwable) {
      E_FAIL
    }

  @JvmStatic
  private fun invokeAsync(self: MemorySegment, operation: MemorySegment, status: Int): Int =
    try {
      callbacks[self.address()]?.asyncInvoke?.invoke(operation, status) ?: return E_FAIL
      S_OK
    } catch (_: Throwable) {
      E_FAIL
    }

  private val QUERY_INTERFACE = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
  private val ADD_REF = FunctionDescriptor.of(JAVA_INT, ADDRESS)
  private val INVOKE_EVENT = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
  private val INVOKE_ASYNC = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)
}
