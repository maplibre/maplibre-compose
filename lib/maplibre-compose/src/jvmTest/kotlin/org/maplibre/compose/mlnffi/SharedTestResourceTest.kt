package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedTestResourceTest {
  @Test
  fun prepared_drivers_keep_the_resource_alive_until_the_last_release() {
    val pending = ArrayDeque<() -> Unit>()
    val disposed = mutableListOf<Any>()
    val shared = SharedTestResource(::Any, disposed::add, pending::addLast)
    val drivers = List(3) { shared.acquire() }
    drivers.forEach { assertSame(drivers.first(), it) }

    drivers.dropLast(1).forEach { driver ->
      shared.release(driver)
      while (pending.isNotEmpty()) pending.removeFirst()()
      assertTrue(disposed.isEmpty(), "An active driver still owns the resource")
    }

    shared.release(drivers.last())
    assertTrue(disposed.isEmpty(), "The idle resource remains reusable during the delay")
    pending.removeFirst()()
    assertEquals(listOf(drivers.first()), disposed)
    val replacement = shared.acquire()
    assertNotSame(drivers.first(), replacement)
    shared.release(replacement)
    pending.removeFirst()()
    assertEquals(listOf(drivers.first(), replacement), disposed)
  }

  @Test
  fun reuse_invalidates_an_earlier_disposal_even_after_another_release() {
    val pending = ArrayDeque<() -> Unit>()
    val disposed = mutableListOf<Any>()
    val shared = SharedTestResource(::Any, disposed::add, pending::addLast)
    val first = shared.acquire()
    shared.release(first)
    val reused = shared.acquire()
    assertSame(first, reused)
    shared.release(reused)

    pending.removeFirst()()
    assertTrue(disposed.isEmpty(), "An old timer must not shorten the new idle delay")
    pending.removeFirst()()
    assertEquals(listOf(first), disposed)
  }
}
