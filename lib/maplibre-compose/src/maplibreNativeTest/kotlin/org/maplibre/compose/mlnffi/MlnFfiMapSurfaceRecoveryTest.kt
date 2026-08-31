package org.maplibre.compose.mlnffi

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MlnFfiMapSurfaceRecoveryTest {

  @Test
  fun a_host_creation_failure_closes_the_renderer_once() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer()
    val showSurface = mutableStateOf(true)
    val failure =
      MlnFfiMapHostResult.Failed(
        "could not create the deliberate test host",
        IllegalStateException("deliberate host creation failure"),
      )

    setContent {
      if (showSurface.value) {
        MlnFfiMapSurface(renderer, failure, Modifier.size(64.dp))
      }
    }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }
    showSurface.value = false
    waitForIdle()
    assertEquals(1, renderer.closeCount)
  }

  @Test
  fun a_host_that_fails_one_acquire_recovers_and_renders_a_later_frame() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer()
    val factory = FakeMlnFfiMapHostFactory(configureHost = { it.failingAcquires = 1 })

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }

    val host = factory.created.single()
    assertEquals(1, renderer.surfaceLostCount)
    assertEquals(
      listOf("onSurfaceAvailable", "onSurfaceLost", "onSurfaceAvailable"),
      renderer.lifecycle,
    )
    assertTrue(host.acquireCount >= 2)
    assertEquals(0, renderer.closeCount)
  }

  @Test
  fun a_skipped_frame_keeps_drawing_the_last_rendered_target() = runFfiComposeUiTest {
    val renderer =
      RecordingMlnFfiMapRenderer(
        renderResults = ArrayDeque(listOf(MlnFfiFrameResult.RENDERED, MlnFfiFrameResult.SKIPPED)),
        additionalFrameRequests = 1,
      )
    val factory = FakeMlnFfiMapHostFactory(configureHost = { it.rotateTargetsOnAcquire = true })

    setSurfaceContent(renderer, factory)
    val host = factory.created.single()
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames >= 2 }
    waitForIdle()

    val renderedTarget = renderer.renderTargets[0]
    val skippedTarget = renderer.renderTargets[1]
    assertNotEquals(renderedTarget, skippedTarget)
    assertEquals(renderer.renderedFrames - 1, host.completedFrames)
    assertTrue(host.drawnTargets.count { it == renderedTarget } >= 2)
    assertFalse(skippedTarget in host.drawnTargets)
    assertTrue(host.leakedFrames.isEmpty())
  }

  @Test
  fun resizes_acquire_targets_for_the_current_draw_size() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer()
    val factory = FakeMlnFfiMapHostFactory()
    val size = mutableStateOf(64.dp)
    val hostResult = factory.create(factory.bridges.single())

    setContent { MlnFfiMapSurface(renderer, hostResult, Modifier.size(size.value)) }
    val host = factory.created.single()
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { host.drawRecords.isNotEmpty() }
    for (nextSize in listOf(96.dp, 48.dp, 80.dp)) {
      val drawsBeforeResize = host.drawRecords.size
      renderer.skipNextRender = true
      size.value = nextSize
      waitUntil(timeoutMillis = TIMEOUT_MILLIS) { host.drawRecords.size > drawsBeforeResize }
      waitForIdle()

      for (draw in host.drawRecords.drop(drawsBeforeResize)) {
        assertEquals(draw.destinationWidth, draw.target.extent.physicalWidth)
        assertEquals(draw.destinationHeight, draw.target.extent.physicalHeight)
      }
    }
  }

  @Test
  fun resize_keeps_presenting_when_the_new_frame_is_skipped() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer()
    val factory = FakeMlnFfiMapHostFactory()
    val size = mutableStateOf(64.dp)
    val hostResult = factory.create(factory.bridges.single())

    setContent { MlnFfiMapSurface(renderer, hostResult, Modifier.size(size.value)) }
    val host = factory.created.single()
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { host.drawRecords.isNotEmpty() }
    val lastRenderedTarget = host.drawRecords.last().target
    val drawsBeforeResize = host.drawRecords.size

    renderer.skipAllRenders = true
    size.value = 257.dp
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.skippedFrames > 0 }
    waitForIdle()

    val resizeDraws = host.drawRecords.drop(drawsBeforeResize)
    assertTrue(resizeDraws.isNotEmpty())
    assertTrue(resizeDraws.all { it.target == lastRenderedTarget })
    for (draw in resizeDraws) {
      assertEquals(draw.target.extent.physicalWidth, draw.destinationWidth)
      assertEquals(draw.target.extent.physicalHeight, draw.destinationHeight)
      assertEquals((draw.scopeWidth - draw.destinationWidth) / 2, draw.destinationLeft)
      assertEquals((draw.scopeHeight - draw.destinationHeight) / 2, draw.destinationTop)
    }
  }

  @Test
  fun resize_aligns_the_completed_frames_camera_anchor_with_the_current_anchor() =
    runFfiComposeUiTest {
      val renderer = RecordingMlnFfiMapRenderer()
      val factory = FakeMlnFfiMapHostFactory()
      val size = mutableStateOf(64.dp)
      val hostResult = factory.create(factory.bridges.single())
      renderer.presentationAnchorOffsetX = 12
      renderer.presentationAnchorOffsetY = -4

      setContent { MlnFfiMapSurface(renderer, hostResult, Modifier.size(size.value)) }
      val host = factory.created.single()
      waitUntil(timeoutMillis = TIMEOUT_MILLIS) { host.drawRecords.isNotEmpty() }
      val completedTarget = host.drawRecords.last().target
      val drawsBeforeResize = host.drawRecords.size

      renderer.presentationAnchorOffsetX = 24
      renderer.presentationAnchorOffsetY = 8
      renderer.skipAllRenders = true
      size.value = 96.dp
      waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.skippedFrames > 0 }
      waitForIdle()

      val resizeDraws = host.drawRecords.drop(drawsBeforeResize)
      assertTrue(resizeDraws.isNotEmpty())
      assertTrue(resizeDraws.all { it.target == completedTarget })
      for (draw in resizeDraws) {
        assertEquals(28, draw.destinationLeft)
        assertEquals(28, draw.destinationTop)
      }
    }

  @Test
  fun render_uses_the_extent_configured_for_the_same_frame() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer()
    val factory = FakeMlnFfiMapHostFactory()

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }

    assertEquals(renderer.renderTargets.first().extent, renderer.surfaceExtentAtRenders.first())
  }

  @Test
  fun extended_not_ready_does_not_consume_recovery() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer()
    val factory =
      FakeMlnFfiMapHostFactory(configureHost = { it.notReadyAcquires = MAX_RECOVERY_ATTEMPTS + 20 })

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }

    assertEquals(0, renderer.surfaceLostCount)
    assertEquals(MAX_RECOVERY_ATTEMPTS + 21, factory.created.single().acquireCount)
    assertEquals(0, renderer.closeCount)
  }

  @Test
  fun not_ready_between_failures_neither_spends_nor_resets_recovery() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer()
    val factory =
      FakeMlnFfiMapHostFactory(
        configureHost = { host ->
          repeat(MAX_RECOVERY_ATTEMPTS) {
            host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.FAILURE
            host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.NOT_READY
          }
          host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.FAILURE
        }
      )

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }

    assertEquals(MAX_RECOVERY_ATTEMPTS * 2 + 1, factory.created.single().acquireCount)
    assertEquals(MAX_RECOVERY_ATTEMPTS, renderer.surfaceLostCount)
  }

  @Test
  fun a_successful_render_resets_recovery() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer(requestAnotherFrame = true)
    val factory =
      FakeMlnFfiMapHostFactory(
        configureHost = { host ->
          host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.FAILURE
          host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.ACQUIRED
          repeat(MAX_RECOVERY_ATTEMPTS + 1) {
            host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.FAILURE
          }
        }
      )

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }

    assertEquals(MAX_RECOVERY_ATTEMPTS + 1, renderer.surfaceLostCount)
    assertEquals(MAX_RECOVERY_ATTEMPTS + 3, factory.created.single().acquireCount)
  }

  @Test
  fun a_renderer_that_fails_one_frame_recovers() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer(failingRenders = 1)
    val factory = FakeMlnFfiMapHostFactory()

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }

    assertEquals(1, renderer.surfaceLostCount)
    assertEquals(0, renderer.closeCount)
  }

  @Test
  fun a_renderer_that_cannot_release_the_lost_surface_stops_recovery() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer(failingSurfaceLosses = 1)
    val factory = FakeMlnFfiMapHostFactory(configureHost = { it.failingAcquires = 1 })

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }

    assertEquals(1, renderer.surfaceLostCount)
    assertEquals(1, factory.created.single().acquireCount)
    assertEquals(listOf("onSurfaceAvailable", "onSurfaceLost"), renderer.lifecycle)
  }

  @Test
  fun repeated_acquire_failure_stops_at_the_bound() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer()
    val factory = FakeMlnFfiMapHostFactory(configureHost = { it.failingAcquires = Int.MAX_VALUE })

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }

    val host = factory.created.single()
    assertEquals(MAX_RECOVERY_ATTEMPTS + 1, host.acquireCount)
    assertEquals(MAX_RECOVERY_ATTEMPTS, renderer.surfaceLostCount)
    waitForIdle()
    assertEquals(MAX_RECOVERY_ATTEMPTS + 1, host.acquireCount)
    assertEquals(1, renderer.closeCount)
  }

  @Test
  fun unexpected_renderer_failure_stops_without_retrying() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer(failingRenders = 1, unexpectedFailure = true)
    val factory = FakeMlnFfiMapHostFactory()

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }

    assertEquals(0, renderer.surfaceLostCount)
    assertEquals(1, factory.created.single().acquireCount)
  }

  @Test
  fun unexpected_host_failure_stops_without_retrying() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer()
    val factory =
      FakeMlnFfiMapHostFactory(
        configureHost = { host ->
          host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.UNEXPECTED_FAILURE
        }
      )

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }

    assertEquals(0, renderer.surfaceLostCount)
    assertEquals(1, factory.created.single().acquireCount)
  }

  @Test
  fun resize_failure_closes_the_renderer_once() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer()
    val factory = FakeMlnFfiMapHostFactory()
    val size = mutableStateOf(64.dp)
    val hostResult = factory.create(factory.bridges.single())

    setContent { MlnFfiMapSurface(renderer, hostResult, Modifier.size(size.value)) }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }
    renderer.failingSurfaceChanges = 1
    size.value = 96.dp
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }
    waitForIdle()
    assertEquals(1, renderer.closeCount)
  }

  private fun ComposeUiTest.setSurfaceContent(
    renderer: MlnFfiMapRenderer,
    factory: FakeMlnFfiMapHostFactory,
  ) {
    val hostResult = factory.create(factory.bridges.single())
    setContent {
      MlnFfiMapSurface(
        renderer = renderer,
        hostResult = hostResult,
        modifier = Modifier.size(64.dp),
        logger = Logger.withTag("surface-recovery-test"),
      )
    }
  }

  private companion object {
    const val MAX_RECOVERY_ATTEMPTS = 3
    const val TIMEOUT_MILLIS = 10_000L
  }
}
