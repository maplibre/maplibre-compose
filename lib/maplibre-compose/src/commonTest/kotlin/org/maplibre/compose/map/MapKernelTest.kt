package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.sources.RasterSource
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
  fun concurrent_style_selections_keep_one_generation() {
    val kernel = kernel()
    kernel.reduce { selectStyle(styleA) }
    val first = kernel.record.styleGeneration
    kernel.reduce { selectStyle(styleB) }
    val second = kernel.record.styleGeneration
    kernel.reduce { selectStyle(styleB) }
    assertTrue(second > first)
    assertEquals(second, kernel.record.styleGeneration)
    assertEquals(styleB, kernel.record.selectedStyle)
    val loading = kernel.record.loadState
    assertIs<MapLoadState.Loading>(loading)
    assertEquals(second, loading.generation)
  }

  @Test
  fun stale_style_completion_cannot_affect_a_newer_selection() {
    val kernel = kernel()
    val source = Any()
    kernel.reduce {
      adoptCore(source)
      selectStyle(styleA)
    }
    val genA = kernel.record.styleGeneration
    kernel.reduce { selectStyle(styleB) }
    val genB = kernel.record.styleGeneration
    kernel.reduce { styleLoadFinished(source, genA) }
    val loading = kernel.record.loadState
    assertIs<MapLoadState.Loading>(loading)
    assertEquals(genB, loading.generation)
    kernel.reduce { styleLoadFinished(source, genB) }
    val ready = kernel.record.loadState
    assertIs<MapLoadState.Ready>(ready)
    assertEquals(genB, ready.generation)
    assertEquals(styleB, ready.style)
  }

  @Test
  fun stale_source_cannot_mutate_after_core_replacement() {
    val kernel = kernel()
    val oldCore = Any()
    val newCore = Any()
    kernel.reduce {
      adoptCore(oldCore)
      selectStyle(styleA)
    }
    val gen = kernel.record.styleGeneration
    kernel.reduce { replaceCore(newCore) }
    kernel.reduce { styleLoadFinished(oldCore, gen) }
    assertIs<MapLoadState.Loading>(kernel.record.loadState)
    kernel.reduce { styleLoadFinished(newCore, gen) }
    assertIs<MapLoadState.Ready>(kernel.record.loadState)
  }

  @Test
  fun close_refuses_attach_and_cannot_race_into_attached() {
    val kernel = kernel()
    val session = Any()
    kernel.reduce { close() }
    assertFailsWith<IllegalStateException> { kernel.reduce { attach(session) } }
    assertNull(kernel.record.session)
    assertTrue(kernel.record.closed)
    assertIs<RendererState.None>(kernel.record.renderer)
  }

  @Test
  fun detach_does_not_strand_events_for_the_next_session() {
    val kernel = kernel()
    val first = Any()
    val second = Any()
    kernel.reduce { attach(first) }
    kernel.reduce { cameraMoveStarted(first, CameraMoveReason.GESTURE) }
    assertTrue(kernel.record.isCameraMoving)
    kernel.reduce { detach(first) }
    assertFalse(kernel.record.isCameraMoving)
    assertNull(kernel.record.viewport)
    kernel.reduce { attach(second) }
    kernel.reduce { cameraMoved(first, CameraPosition(zoom = 9.0), testViewport()) }
    assertEquals(2.0, kernel.record.camera.zoom)
    kernel.reduce { cameraMoved(second, CameraPosition(zoom = 4.0), testViewport()) }
    assertEquals(4.0, kernel.record.camera.zoom)
    assertEquals(DpSize(100.dp, 80.dp), kernel.record.viewport?.size)
  }

  @Test
  fun camera_writes_are_linearly_ordered() {
    val kernel = kernel()
    val adapter = Any()
    kernel.reduce { attach(adapter) }
    kernel.reduce { setCamera(CameraPosition(zoom = 3.0)) }
    val firstSeq = kernel.record.cameraWriteSeq
    kernel.reduce { setCamera(CameraPosition(zoom = 5.0)) }
    assertTrue(kernel.record.cameraWriteSeq > firstSeq)
    assertEquals(5.0, kernel.record.camera.zoom)
    kernel.reduce { publishFittedCamera(CameraPosition(zoom = 7.0), testViewport()) }
    assertEquals(7.0, kernel.record.camera.zoom)
    assertTrue(kernel.record.cameraWriteSeq > firstSeq)
  }

  @Test
  fun surface_loss_clears_viewport_until_a_matching_replacement() {
    val kernel = kernel()
    val session = Any()
    kernel.reduce { attach(session) }
    kernel.reduce { cameraMoved(session, CameraPosition(zoom = 3.0), testViewport()) }
    assertTrue(kernel.record.hasAuthoritativeSurface)
    assertTrue(kernel.record.viewport != null)
    val surface = kernel.record.surfaceGeneration
    kernel.reduce { surfaceLost(session, surface) }
    assertFalse(kernel.record.hasAuthoritativeSurface)
    assertNull(kernel.record.viewport)
    kernel.reduce { surfaceLost(session, surface - 1) }
    assertNull(kernel.record.viewport)
    kernel.reduce { cameraMoved(session, CameraPosition(zoom = 3.0), testViewport()) }
    assertTrue(kernel.record.hasAuthoritativeSurface)
  }

  @Test
  fun cancelled_operation_does_not_publish_a_later_camera() {
    val kernel = kernel()
    val (id, _) = kernel.reduceValue { beginOperation() }
    kernel.reduce { cancelOperation(id) }
    assertFalse(kernel.record.isOperationActive(id))
    kernel.reduce { completeCameraOperation(id, CameraPosition(zoom = 12.0), testViewport()) }
    assertEquals(2.0, kernel.record.camera.zoom)
  }

  @Test
  fun composition_publish_after_close_or_binding_replacement_is_ignored() {
    val kernel = kernel()
    val source = Any()
    val firstBinding = StyleBinding.UNLOADED
    kernel.reduce {
      adoptCore(source)
      styleChanged(source, firstBinding, 0L)
    }
    val generation = kernel.record.bindingGeneration
    kernel.reduce { close() }
    val accepted =
      kernel.reduceValue { commitComposition(firstBinding, setOf("roads"), emptyMap()) }.first
    assertFalse(accepted)
    assertTrue(kernel.record.compositionLayerIds.isEmpty())
    assertTrue(generation < kernel.record.bindingGeneration)
  }

  @Test
  fun rejected_session_cannot_take_the_slot() {
    val kernel = kernel()
    val winner = Any()
    val rival = Any()
    kernel.reduce { attach(winner) }
    val token = kernel.record.sessionToken
    assertFailsWith<IllegalStateException> { kernel.reduce { attach(rival) } }
    assertSame(winner, kernel.record.session)
    assertEquals(token, kernel.record.sessionToken)
  }

  @Test
  fun capture_rejects_an_attached_session_and_close_releases_the_lease() {
    val kernel = kernel()
    val session = Any()
    kernel.reduce { attach(session) }
    assertFailsWith<IllegalStateException> { kernel.reduce { beginCapture() } }
    kernel.reduce { detach(session) }
    val (id, _) = kernel.reduceValue { beginCapture() }
    assertIs<RendererState.Capture>(kernel.record.renderer)
    kernel.reduce { close() }
    assertIs<RendererState.None>(kernel.record.renderer)
    assertNotEquals(0L, id)
  }

  @Test
  fun app_source_commit_is_atomic_with_the_current_binding() {
    val kernel = kernel()
    val source = Any()
    kernel.reduce {
      adoptCore(source)
      styleChanged(source, StyleBinding.UNLOADED, 0L)
    }
    val binding = kernel.record.binding
    val added =
      kernel
        .reduceValue { commitAppSource(binding, RasterSource("pts", "https://example.invalid")) }
        .first
    assertFalse(added, "an unloaded binding cannot publish an app source")
  }

  @Test
  fun first_config_owner_wins_and_a_rival_cannot_claim() {
    val kernel = kernel()
    val winner = Any()
    val rival = Any()
    val (accepted, _) = kernel.reduceValue { claimConfig(winner) }
    assertTrue(accepted)
    val (rejected, _) = kernel.reduceValue { claimConfig(rival) }
    assertFalse(rejected)
    assertSame(winner, kernel.record.configOwner)
    kernel.reduce { releaseConfig(winner) }
    val (second, _) = kernel.reduceValue { claimConfig(rival) }
    assertTrue(second)
    assertSame(rival, kernel.record.configOwner)
  }

  @Test
  fun layer_write_authorization_is_atomic_with_the_current_generation() {
    val kernel = kernel()
    val source = Any()
    kernel.reduce {
      adoptCore(source)
      styleChanged(source, StyleBinding.UNLOADED, 0L)
    }
    val generation = kernel.record.styleGeneration
    assertNull(kernel.record.authorizeLayerWrite(generation, "roads"))
    kernel.reduce { selectStyle(styleA) }
    val next = kernel.record.styleGeneration
    kernel.reduce { styleChanged(source, StyleBinding.UNLOADED, next) }
    assertNull(kernel.record.authorizeLayerWrite(generation, "roads"))
    assertFalse(kernel.record.confirmLayerWrite(generation, StyleBinding.UNLOADED))
  }

  @Test
  fun camera_operation_publishes_before_it_completes() {
    val kernel = kernel()
    val (id, _) = kernel.reduceValue { beginOperation() }
    val effects = kernel.reduce {
      completeCameraOperation(id, CameraPosition(zoom = 11.0), testViewport())
    }
    assertEquals(11.0, kernel.record.camera.zoom)
    assertEquals(DpSize(100.dp, 80.dp), kernel.record.viewport?.size)
    assertTrue(effects.any { it is MapEffect.ResumeOperation && it.operationId == id })
  }

  @Test
  fun close_cancels_pending_operations() {
    val kernel = kernel()
    val (id, _) = kernel.reduceValue { beginOperation() }
    kernel.reduce { close() }
    assertFalse(kernel.record.isOperationActive(id))
    kernel.reduce { completeCameraOperation(id, CameraPosition(zoom = 8.0), testViewport()) }
    assertEquals(2.0, kernel.record.camera.zoom)
  }

  @Test
  fun reentrant_reduce_from_an_effect_does_not_deadlock() {
    val kernel = kernel()
    val session = Any()
    val effects = kernel.reduce { attach(session) }
    assertTrue(effects.isNotEmpty())
    kernel.reduce { setCamera(CameraPosition(zoom = 6.0)) }
    assertEquals(6.0, kernel.record.camera.zoom)
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
