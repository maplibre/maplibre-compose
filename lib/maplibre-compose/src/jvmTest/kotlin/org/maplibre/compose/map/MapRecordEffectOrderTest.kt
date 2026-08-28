package org.maplibre.compose.map

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition

class MapRecordEffectOrderTest {

  @Test
  fun concurrent_commits_run_effects_in_record_order() {
    val record = MapRecord(CameraPosition())
    val log = Collections.synchronizedList(mutableListOf<String>())
    val startedA = CountDownLatch(1)
    val releaseA = CountDownLatch(1)
    val committedB = CountDownLatch(1)

    val first = Thread {
      record.mutate {
        enqueue {
          log += "A-start"
          startedA.countDown()
          assertTrue(releaseA.await(5, TimeUnit.SECONDS))
          log += "A-end"
        }
      }
      record.drain()
    }
    first.start()
    assertTrue(startedA.await(5, TimeUnit.SECONDS))

    val second = Thread {
      record.mutate { enqueue { log += "B" } }
      committedB.countDown()
      record.drain()
    }
    second.start()
    assertTrue(committedB.await(5, TimeUnit.SECONDS))
    Thread.sleep(50)
    releaseA.countDown()
    first.join(5_000)
    second.join(5_000)

    assertEquals(listOf("A-start", "A-end", "B"), log.toList())
  }

  @Test
  fun a_callback_drain_does_not_wait_for_the_active_drainer() {
    val record = MapRecord(CameraPosition())
    val log = Collections.synchronizedList(mutableListOf<String>())
    val startedA = CountDownLatch(1)
    val releaseOwner = CountDownLatch(1)

    val drainer = Thread {
      record.mutate {
        enqueue {
          log += "A-start"
          startedA.countDown()
          assertTrue(releaseOwner.await(5, TimeUnit.SECONDS))
          log += "A-end"
        }
      }
      record.drain()
    }
    drainer.start()
    assertTrue(startedA.await(5, TimeUnit.SECONDS))

    val callback = Thread {
      record.mutate { enqueue { log += "C" } }
      record.drain(waitForIdle = false)
      log += "callback-returned"
    }
    callback.start()
    callback.join(5_000)
    assertTrue(!callback.isAlive, "a platform callback must not wait for the active drain")

    releaseOwner.countDown()
    drainer.join(5_000)
    assertTrue(!drainer.isAlive)
    assertEquals(listOf("A-start", "callback-returned", "A-end", "C"), log.toList())
  }
}
