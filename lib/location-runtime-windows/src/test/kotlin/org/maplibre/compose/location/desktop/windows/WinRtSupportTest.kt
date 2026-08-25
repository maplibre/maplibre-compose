package org.maplibre.compose.location.desktop.windows

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class WinRtSupportTest {
  @Test
  fun eventCallbackRemainsAliveUntilTheLastNativeReferenceIsReleased() {
    val invocations = AtomicInteger()
    val callback =
      WinRtEventCallback.create("df3c6164-4e7b-5e8e-9a7e-13da059dec1e") { _, _ ->
        invocations.incrementAndGet()
      }
    val nativeReference = callback.segment

    assertEquals(2, callReferenceMethod(nativeReference, ADD_REF))
    callback.close()

    assertEquals(S_OK, invokeEvent(nativeReference))
    assertEquals(1, invocations.get())
    assertEquals(0, callReferenceMethod(nativeReference, RELEASE))
    assertEquals(E_FAIL, invokeEvent(nativeReference))
  }

  private fun callReferenceMethod(instance: MemorySegment, slot: Int): Int =
    WinRt.call(instance, slot, FunctionDescriptor.of(JAVA_INT, ADDRESS)) as Int

  private fun invokeEvent(instance: MemorySegment): Int =
    WinRt.call(
      instance,
      INVOKE,
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
      MemorySegment.NULL,
      MemorySegment.NULL,
    ) as Int

  private companion object {
    const val S_OK = 0
    const val E_FAIL = -2_147_467_259
    const val ADD_REF = 1
    const val RELEASE = 2
    const val INVOKE = 3
  }
}
