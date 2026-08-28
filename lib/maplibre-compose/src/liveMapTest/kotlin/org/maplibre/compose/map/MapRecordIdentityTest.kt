package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

class MapRecordIdentityTest {

  private val start = CameraPosition()
  private val moved = CameraPosition(target = Position(1.0, 2.0))
  private val surface =
    Viewport(
      size = DpSize(100.dp, 80.dp),
      visibleBoundingBox =
        BoundingBox(southwest = Position(0.0, 0.0), northeast = Position(1.0, 1.0)),
      visibleRegion =
        VisibleRegion(
          Position(0.0, 1.0),
          Position(1.0, 1.0),
          Position(0.0, 0.0),
          Position(1.0, 0.0),
        ),
      metersPerDpAtTarget = 1.0,
    )

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
    record.mutate { cameraMoved(adapter, moved, surface) }
    assertFalse(record.read { isCameraMoving })
    assertEquals(start, record.read { camera })
    assertSame(surface, record.read { viewport })
  }

  @Test
  fun detach_removes_bound_operations_from_the_registry() {
    val record = MapRecord(start)
    val adapter = FakeMapAdapter()
    val op = record.mutate {
      attach(adapter)
      val id = beginOperation()
      bindOperation(id, adapter)
      id
    }
    record.mutate { detach(adapter) }
    assertNull(record.read { pendingOperations[op] })
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
  fun a_style_or_camera_write_during_capture_does_not_change_the_frozen_inputs() {
    val record = MapRecord(start)
    val adapter = FakeMapAdapter()
    record.mutate { attach(adapter) }
    record.mutate { detach(adapter) }
    record.drain()
    val stylesBefore = adapter.calls.count { it == "setBaseStyle" }
    val lease = record.mutate { beginCapture() }
    val frozen = record.read { renderer as RendererState.Capture }
    record.mutate { setCamera(moved) }
    record.mutate { selectStyle(BaseStyle.Empty) }
    record.drain()
    val after = record.read { renderer as RendererState.Capture }
    assertEquals(frozen.camera, after.camera)
    assertEquals(frozen.style, after.style)
    assertEquals(frozen.styleGeneration, after.styleGeneration)
    assertEquals(moved, record.read { camera })
    assertEquals(BaseStyle.Empty, record.read { selectedStyle })
    assertEquals(
      stylesBefore + 1,
      adapter.calls.count { it == "setBaseStyle" },
      "capture must not push a later baseStyle to the renderer",
    )
    record.mutate { finishCapture(lease) }
    record.drain()
    assertTrue(
      adapter.calls.count { it == "setBaseStyle" } > stylesBefore + 1,
      "finishing capture must apply the style that arrived during it",
    )
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
