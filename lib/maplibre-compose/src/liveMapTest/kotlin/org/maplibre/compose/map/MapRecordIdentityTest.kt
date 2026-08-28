package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

class MapRecordIdentityTest {

  private val start = CameraPosition()
  private val moved = CameraPosition(target = Position(1.0, 2.0))

  @Test
  fun a_bound_operation_publishes_into_the_session_that_accepted_it() {
    val record = MapRecord(start)
    val adapter = FakeMapAdapter()
    record.mutate {
      attach(adapter)
      val id = beginOperation()
      check(bindOperation(id, adapter))
      completeCameraOperation(id, moved, null)
    }
    assertEquals(moved, record.read { camera })
  }

  @Test
  fun a_camera_operation_from_a_detached_session_does_not_publish() {
    val record = MapRecord(start)
    val first = FakeMapAdapter()
    val second = FakeMapAdapter()
    val op = record.mutate {
      attach(first)
      val id = beginOperation()
      bindOperation(id, first)
      id
    }
    record.mutate {
      detach(first)
      attach(second)
      completeCameraOperation(op, moved, null)
    }
    assertEquals(start, record.read { camera })
  }

  @Test
  fun an_unbound_camera_operation_does_not_publish_into_a_later_session() {
    val record = MapRecord(start)
    val first = FakeMapAdapter()
    val second = FakeMapAdapter()
    val op = record.mutate {
      attach(first)
      beginOperation()
    }
    record.mutate {
      detach(first)
      attach(second)
      completeCameraOperation(op, moved, null)
    }
    assertEquals(start, record.read { camera })
  }

  @Test
  fun bind_operation_refuses_an_adapter_that_is_not_the_current_session() {
    val record = MapRecord(start)
    val first = FakeMapAdapter()
    val second = FakeMapAdapter()
    val op = record.mutate {
      attach(first)
      beginOperation()
    }
    val bound = record.mutate {
      detach(first)
      attach(second)
      bindOperation(op, first)
    }
    assertFalse(bound)
    record.mutate { completeCameraOperation(op, moved, null) }
    assertEquals(start, record.read { camera })
  }

  @Test
  fun a_same_adapter_reattach_does_not_complete_the_previous_session_operation() {
    val record = MapRecord(start)
    val adapter = FakeMapAdapter()
    val op = record.mutate {
      attach(adapter)
      val id = beginOperation()
      bindOperation(id, adapter)
      id
    }
    record.mutate {
      detach(adapter)
      attach(adapter)
      completeCameraOperation(op, moved, null)
    }
    assertEquals(start, record.read { camera })
  }

  @Test
  fun leftover_programmatic_camera_events_do_not_mark_a_reattached_session_moving() {
    val record = MapRecord(start)
    val adapter = FakeMapAdapter()
    record.mutate { attach(adapter) }
    record.mutate { cameraMoveStarted(adapter, CameraMoveReason.PROGRAMMATIC) }
    assertTrue(record.read { isCameraMoving })
    record.mutate { detach(adapter) }
    assertFalse(record.read { isCameraMoving })
    record.mutate { attach(adapter) }
    record.mutate { cameraMoveStarted(adapter, CameraMoveReason.PROGRAMMATIC) }
    record.mutate { cameraMoved(adapter, moved, null) }
    assertFalse(record.read { isCameraMoving })
    assertEquals(start, record.read { camera })
  }

  @Test
  fun a_gesture_after_reattach_marks_the_new_session_moving() {
    val record = MapRecord(start)
    val adapter = FakeMapAdapter()
    record.mutate { attach(adapter) }
    record.mutate { detach(adapter) }
    record.mutate { attach(adapter) }
    record.mutate { cameraMoveStarted(adapter, CameraMoveReason.GESTURE) }
    assertTrue(record.read { isCameraMoving })
  }

  @Test
  fun a_new_session_generation_accepts_programmatic_moves_after_it_arms_camera_work() {
    val record = MapRecord(start)
    val adapter = FakeMapAdapter()
    record.mutate { attach(adapter) }
    record.mutate { detach(adapter) }
    record.mutate { attach(adapter) }
    record.mutate { setCamera(moved) }
    record.mutate { cameraMoveStarted(adapter, CameraMoveReason.PROGRAMMATIC) }
    assertTrue(record.read { isCameraMoving })
    assertEquals(moved, record.read { camera })
  }

  @Test
  fun a_second_capture_does_not_replace_the_active_lease() {
    val record = MapRecord(start)
    val first = record.mutate { beginCapture() }
    assertFailsWith<IllegalStateException> { record.mutate { beginCapture() } }
    assertIs<RendererState.Capture>(record.read { renderer })
    assertEquals(first, (record.read { renderer } as RendererState.Capture).id)
    record.mutate { finishCapture(first) }
    val second = record.mutate { beginCapture() }
    record.mutate { finishCapture(second) }
    assertIs<RendererState.None>(record.read { renderer })
  }

  @Test
  fun a_stale_capture_finish_does_not_release_a_newer_lease() {
    val record = MapRecord(start)
    val first = record.mutate { beginCapture() }
    record.mutate { finishCapture(first) }
    val second = record.mutate { beginCapture() }
    record.mutate { finishCapture(first) }
    assertIs<RendererState.Capture>(record.read { renderer })
    assertEquals(second, (record.read { renderer } as RendererState.Capture).id)
  }

  @Test
  fun a_failed_retained_style_retries_on_reattach() {
    val record = MapRecord(start)
    val adapter = FakeMapAdapter()
    record.mutate { attach(adapter) }
    record.drain()
    val generation = record.read { styleGeneration }
    record.mutate { styleLoadFailed(adapter, generation, "style failed") }
    record.mutate {
      detach(adapter)
      attach(adapter)
    }
    record.drain()
    assertTrue(record.read { styleGeneration } > generation)
    assertIs<MapLoadState.Loading>(record.read { loadState })
    assertEquals(2, adapter.calls.count { it == "setBaseStyle" })
  }

  @Test
  fun close_rejects_a_racing_style_composition() {
    val record = MapRecord(start)
    val accepted = record.mutate { replaceStyleComposition {} }
    assertTrue(accepted)
    record.mutate { close() }
    assertTrue(!record.mutate { replaceStyleComposition {} })
    assertEquals(null, record.read { styleComposition })
  }
}
