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
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

class MapKernelTest {

  private val styleA = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
  private val styleB = BaseStyle.Uri("https://example.invalid/b.json")

  private fun kernel() = MapKernel(CameraPosition(zoom = 2.0))

  @Test
  fun a_newer_style_generation_ignores_stale_completions() {
    val kernel = kernel()
    val source = Any()
    kernel.reduce { replaceCore(source) }
    kernel.reduce { selectStyle(styleA) }
    val genA = kernel.record.styleGeneration
    kernel.reduce { selectStyle(styleB) }
    val genB = kernel.record.styleGeneration
    kernel.reduce { selectStyle(styleB) }
    assertEquals(genB, kernel.record.styleGeneration)
    kernel.reduce { styleLoadFinished(source, genA) }
    kernel.reduce { styleLoadFailed(null, genB, "orphaned") }
    assertIs<MapLoadState.Loading>(kernel.record.loadState)
    kernel.reduce { styleLoadFinished(source, genB) }
    val ready = kernel.record.loadState
    assertIs<MapLoadState.Ready>(ready)
    assertEquals(genB, ready.generation)
    assertEquals(styleB, ready.style)
  }

  @Test
  fun a_replaced_core_cannot_finish_the_previous_source() {
    val kernel = kernel()
    val oldCore = Any()
    val newCore = Any()
    kernel.reduce { replaceCore(oldCore) }
    kernel.reduce { selectStyle(styleA) }
    val gen = kernel.record.styleGeneration
    kernel.reduce { replaceCore(newCore) }
    kernel.reduce { styleLoadFinished(oldCore, gen) }
    assertIs<MapLoadState.Loading>(kernel.record.loadState)
    kernel.reduce { styleLoadFinished(newCore, gen) }
    assertIs<MapLoadState.Ready>(kernel.record.loadState)
  }

  @Test
  fun the_session_slot_is_exclusive_and_close_releases_it() {
    val kernel = kernel()
    val winner = Any()
    kernel.reduce { attach(winner) }
    assertFailsWith<IllegalStateException> { kernel.reduce { attach(Any()) } }
    assertSame(winner, kernel.record.session)
    assertFailsWith<IllegalStateException> { kernel.reduce { beginCapture() } }
    kernel.reduce { detach(winner) }
    kernel.reduce { beginCapture() }
    assertIs<RendererState.Capture>(kernel.record.renderer)
    kernel.reduce { close() }
    assertIs<RendererState.None>(kernel.record.renderer)
    assertFailsWith<IllegalStateException> { kernel.reduce { attach(Any()) } }
  }

  @Test
  fun detach_and_a_foreign_session_cannot_move_the_camera() {
    val kernel = kernel()
    val first = Any()
    val second = Any()
    kernel.reduce { attach(first) }
    kernel.reduce { cameraMoveStarted(first, CameraMoveReason.GESTURE) }
    kernel.reduce { detach(first) }
    assertFalse(kernel.record.isCameraMoving)
    assertNull(kernel.record.viewport)
    kernel.reduce { attach(second) }
    kernel.reduce { cameraMoved(first, CameraPosition(zoom = 9.0), testViewport()) }
    assertEquals(2.0, kernel.record.camera.zoom)
    kernel.reduce { cameraMoved(second, CameraPosition(zoom = 4.0), testViewport()) }
    assertEquals(4.0, kernel.record.camera.zoom)
  }

  @Test
  fun a_detached_camera_write_is_recorded_and_not_sent() {
    val kernel = kernel()
    val core = Any()
    kernel.reduce { replaceCore(core) }
    val effects = kernel.reduce { setCamera(CameraPosition(zoom = 5.0)) }
    assertEquals(5.0, kernel.record.camera.zoom)
    assertTrue(effects.none { it is MapEffect.SendCamera })
    kernel.reduce { attach(core) }
    val attachEffects = kernel.reduce { setCamera(CameraPosition(zoom = 6.0)) }
    assertTrue(attachEffects.any { it is MapEffect.SendCamera })
  }

  @Test
  fun a_foreign_session_cannot_clear_the_viewport() {
    val kernel = kernel()
    val session = Any()
    kernel.reduce { attach(session) }
    kernel.reduce { cameraMoved(session, CameraPosition(zoom = 3.0), testViewport()) }
    assertTrue(kernel.record.hasAuthoritativeSurface)
    kernel.reduce { surfaceLost(Any()) }
    assertTrue(kernel.record.hasAuthoritativeSurface)
    kernel.reduce { surfaceLost(session) }
    assertFalse(kernel.record.hasAuthoritativeSurface)
    assertNull(kernel.record.viewport)
  }

  @Test
  fun a_cancelled_or_closed_operation_does_not_publish() {
    val kernel = kernel()
    val (id, _) = kernel.reduceValue { beginOperation() }
    kernel.reduce { cancelOperation(id) }
    kernel.reduce { completeCameraOperation(id, CameraPosition(zoom = 12.0), testViewport()) }
    assertEquals(2.0, kernel.record.camera.zoom)
    val (closedId, _) = kernel.reduceValue { beginOperation() }
    kernel.reduce { close() }
    kernel.reduce { completeCameraOperation(closedId, CameraPosition(zoom = 8.0), testViewport()) }
    assertEquals(2.0, kernel.record.camera.zoom)
  }

  @Test
  fun close_or_a_new_binding_refuses_stale_composition_and_layer_writes() {
    val kernel = kernel()
    val source = Any()
    kernel.reduce { replaceCore(source) }
    kernel.reduce { styleChanged(source, StyleBinding.UNLOADED, 0L) }
    val styleGeneration = kernel.record.styleGeneration
    val bindingGeneration = kernel.record.bindingGeneration
    assertNull(kernel.record.authorizeLayerWrite(styleGeneration, bindingGeneration, "roads"))
    kernel.reduce { close() }
    assertFalse(
      kernel
        .reduceValue { commitComposition(StyleBinding.UNLOADED, setOf("roads"), emptyMap()) }
        .first
    )
    assertTrue(kernel.record.compositionLayerIds.isEmpty())
  }

  @Test
  fun reattach_of_a_ready_style_source_does_not_reload() {
    val kernel = kernel()
    val core = Any()
    kernel.reduce {
      attach(core)
      selectStyle(styleA)
    }
    kernel.reduce { styleLoadFinished(core, kernel.record.styleGeneration) }
    kernel.reduce { detach(core) }
    val effects = kernel.reduce { attach(core) }
    assertTrue(effects.none { it is MapEffect.LoadStyle })
    assertIs<MapLoadState.Ready>(kernel.record.loadState)
  }
}

private fun testViewport(): Viewport =
  Viewport(
    size = DpSize(100.dp, 80.dp),
    visibleBoundingBox =
      BoundingBox(southwest = Position(0.0, 0.0), northeast = Position(1.0, 1.0)),
    visibleRegion =
      VisibleRegion(Position(0.0, 1.0), Position(1.0, 1.0), Position(0.0, 0.0), Position(1.0, 0.0)),
    metersPerDpAtTarget = 1.0,
  )
