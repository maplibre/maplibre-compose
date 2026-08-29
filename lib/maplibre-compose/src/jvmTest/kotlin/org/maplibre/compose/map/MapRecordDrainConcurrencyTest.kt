package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.camera.CameraPosition

class MapRecordDrainConcurrencyTest {

  @Test
  fun a_commit_during_flush_is_not_left_queued() {
    val record = MapRecord(CameraPosition())
    val log = mutableListOf<String>()

    record.mutate {
      enqueue {
        log += "A"
        record.mutate { enqueue { log += "B" } }
      }
    }
    record.drain()

    assertEquals(
      listOf("A", "B"),
      log,
      "work enqueued while this drain is flushing must run before drain returns",
    )
  }
}
