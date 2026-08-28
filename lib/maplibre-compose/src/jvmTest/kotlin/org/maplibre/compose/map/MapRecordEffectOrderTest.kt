package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.camera.CameraPosition

class MapRecordEffectOrderTest {

  @Test
  fun effects_run_in_enqueue_order_on_the_logical_thread() {
    val record = MapRecord(CameraPosition())
    val log = mutableListOf<String>()
    record.mutate {
      enqueue { log += "A" }
      enqueue { log += "B" }
    }
    record.drain()
    record.mutate { enqueue { log += "C" } }
    record.drain()
    assertEquals(listOf("A", "B", "C"), log)
  }
}
