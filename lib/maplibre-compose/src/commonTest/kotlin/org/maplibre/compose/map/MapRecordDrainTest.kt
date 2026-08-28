package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition

class MapRecordDrainTest {

  private fun MapRecord.commit(transform: MapRecord.() -> Unit) {
    mutate(transform)
    drain()
  }

  @Test
  fun a_reentrant_commit_runs_its_effects_behind_the_current_drain() {
    val record = MapRecord(CameraPosition())
    val log = mutableListOf<String>()
    record.commit {
      enqueue {
        log += "A-start"
        record.commit { enqueue { log += "C" } }
        log += "A-end"
      }
      enqueue { log += "B" }
    }
    assertEquals(listOf("A-start", "A-end", "B", "C"), log)
  }

  @Test
  fun current_thread_token_is_stable() {
    val first = currentThreadToken()
    val second = currentThreadToken()
    assertTrue(first === second, "the drain compares tokens with identity")
  }

  @Test
  fun two_commits_run_platform_work_in_commit_order() {
    val record = MapRecord(CameraPosition())
    val log = mutableListOf<String>()
    record.commit { enqueue { log += "A" } }
    record.commit { enqueue { log += "B" } }
    assertEquals(listOf("A", "B"), log)
  }
}
