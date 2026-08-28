package org.maplibre.compose.map

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.camera.CameraPosition

class MapRecordDrainConcurrencyTest {

  @Test
  fun a_commit_during_flush_is_not_left_queued() {
    val record = MapRecord(CameraPosition())
    val log = mutableListOf<String>()
    val started = CountDownLatch(1)
    val released = CountDownLatch(1)

    val flusher = thread {
      record.mutate {
        enqueue {
          log += "A"
          started.countDown()
          check(released.await(5, TimeUnit.SECONDS)) { "the overlapping commit never arrived" }
        }
      }
      record.drain()
    }

    check(started.await(5, TimeUnit.SECONDS)) { "the flush never started" }
    record.mutate { enqueue { log += "B" } }
    record.drain()
    released.countDown()
    flusher.join(5_000)
    check(!flusher.isAlive) { "the flush never finished" }

    assertEquals(
      listOf("A", "B"),
      log,
      "work enqueued while another thread is flushing must run before drain returns",
    )
  }
}
