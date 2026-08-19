package org.maplibre.compose.testing

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecordingListTest {
  @Test
  fun iterators_survive_concurrent_appends() {
    val list = RecordingList<Int>()
    val stop = AtomicBoolean(false)
    val writerStarted = CyclicBarrier(2)
    val writer = thread {
      writerStarted.await()
      var value = 0
      while (!stop.get()) {
        list += value++
      }
    }
    try {
      writerStarted.await()
      repeat(20_000) {
        val snapshot = list.toList()
        assertEquals(snapshot.size, snapshot.count { true })
        list.count { it >= 0 }
        if (snapshot.isNotEmpty()) {
          assertTrue(list.contains(snapshot.first()))
        }
      }
    } finally {
      stop.set(true)
      writer.join()
    }
  }

  @Test
  fun iterator_remove_is_refused() {
    val list = RecordingList<Int>()
    list += 1
    val iterator = list.iterator()
    assertTrue(iterator.hasNext())
    iterator.next()
    assertFailsWith<UnsupportedOperationException> { iterator.remove() }
    assertEquals(listOf(1), list.toList())
  }
}
