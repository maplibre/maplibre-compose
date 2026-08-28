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
}
