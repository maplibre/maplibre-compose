package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult

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
    state.commit { finishCapture(lease.id) }
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
  fun a_closed_state_registers_without_attaching() {
    val state = MapState(CameraPosition())
    state.close()
    val resource = RecordingSessionResource()
    bindMapSession(resource, state) { state.attachSession(it) }
    assertTrue(resource.registered)
    assertFalse(state.isAttached)
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

  @Test
  fun the_still_image_pump_sees_a_frozen_generation_failure() {
    val state = MapState(CameraPosition())
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    val frozen = state.record.read { styleGeneration }
    state.detachSession()
    state.commit { beginCapture() }
    state.baseStyle = BaseStyle.Empty
    state.commit { styleLoadFailed(adapter, frozen, "frozen failed") }
    assertEquals("frozen failed", state.captureRenderFailure(frozen))
    assertNull(state.captureRenderFailure(frozen + 1))
    state.commit { finishCapture(state.record.read { (renderer as RendererState.Capture).id }) }
    state.close()
  }

  @Test
  fun a_rival_composition_cannot_apply_session_environment() {
    val state = MapState(CameraPosition(), density = Density(1f))
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    val owner = Any()
    val rival = Any()
    val scope = CoroutineScope(Dispatchers.Unconfined)
    assertTrue(
      state.publishSessionEnvironment(
        owner = owner,
        density = Density(2f),
        layoutDirection = LayoutDirection.Ltr,
        inheritedLocals = null,
        options = sessionOptions(zoomRange = 1f..10f),
        onMapClick = { _, _ -> ClickResult.Pass },
        onMapLongClick = { _, _ -> ClickResult.Pass },
        onFrame = {},
        clickScope = scope,
      )
    )
    val constraintWrites = adapter.calls.count { it == "setCameraConstraints" }
    assertFalse(
      state.publishSessionEnvironment(
        owner = rival,
        density = Density(3f),
        layoutDirection = LayoutDirection.Rtl,
        inheritedLocals = null,
        options = sessionOptions(zoomRange = 4f..8f),
        onMapClick = { _, _ -> ClickResult.Pass },
        onMapLongClick = { _, _ -> ClickResult.Pass },
        onFrame = {},
        clickScope = scope,
      )
    )
    assertEquals(2f, state.density.density)
    assertEquals(LayoutDirection.Ltr, state.layoutDirection)
    assertEquals(constraintWrites, adapter.calls.count { it == "setCameraConstraints" })
    state.detachSession()
    assertEquals(1f, state.density.density)
    assertNull(state.sessionEnvironmentOwner)
    state.close()
  }
}

private fun sessionOptions(zoomRange: ClosedRange<Float>) =
  SessionOptions(
    cameraPadding = PaddingValues(0.dp),
    zoomRange = zoomRange,
    pitchRange = 0f..60f,
    boundingBox = null,
    options = MapOptions(),
  )

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
