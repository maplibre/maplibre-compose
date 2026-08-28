package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition

class MapSessionBindTest {

  @Test
  fun a_refused_record_attach_does_not_register_the_engine() {
    val state = MapState(CameraPosition())
    val lease = state.commit { beginCapture() }
    val resource = RecordingSessionResource()
    val error =
      assertFailsWith<IllegalStateException> {
        bindMapSession(resource, state) { state.attachSession(it) }
      }
    assertTrue(error.message == SNAPSHOT_SESSION_ERROR)
    assertFalse(resource.registered)
    assertNull(state.attachedAdapter)
    state.commit { finishCapture(lease) }
    state.close()
  }

  @Test
  fun a_failed_engine_register_detaches_the_record() {
    val state = MapState(CameraPosition())
    val resource = RecordingSessionResource(registerAction = { error("engine refused") })
    assertFailsWith<IllegalStateException> {
      bindMapSession(resource, state) { state.attachSession(it) }
    }
    assertFalse(resource.registered)
    assertNull(state.attachedAdapter)
    state.close()
  }

  @Test
  fun a_failed_register_does_not_detach_an_already_attached_core() {
    val state = MapState(CameraPosition())
    val core = FakeMapAdapter()
    val first = RecordingSessionResource(session = core)
    bindMapSession(first, state) { state.attachSession(core) }
    val rival = RecordingSessionResource(registerAction = { error("engine refused") })
    assertFailsWith<IllegalStateException> {
      bindMapSession(rival, state) { state.attachSession(core) }
    }
    assertTrue(state.isAttached)
    assertSame(core, state.attachedAdapter)
    state.detachSession()
    first.release()
    state.close()
  }

  @Test
  fun a_successful_bind_registers_after_the_record_accepts() {
    val state = MapState(CameraPosition())
    val resource = RecordingSessionResource()
    bindMapSession(resource, state) { state.attachSession(it) }
    assertTrue(resource.registered)
    assertTrue(state.isAttached)
    state.detachSession()
    resource.release()
    state.close()
  }
}

private class RecordingSessionResource(
  private val registerAction: () -> Unit = {},
  override val session: FakeMapAdapter = FakeMapAdapter(),
) : MapSessionResource<FakeMapAdapter> {
  var registered: Boolean = false
    private set

  override fun register() {
    registerAction()
    registered = true
  }

  override fun release() {}
}
