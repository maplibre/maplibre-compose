package org.maplibre.compose.mlnffi

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.MapExtent

@OptIn(ExperimentalTestApi::class)
class MlnFfiMapSurfaceRecoveryTest {

  @Test
  fun asynchronous_frames_coalesce_requests_and_publish_only_completed_projections() {
    val renderer = RecordingRenderer()
    val host = FakeMlnFfiMapHost().apply { rotateTargetsOnAcquire = true }
    val queued = ArrayDeque<() -> Unit>()
    val closedProjections = mutableListOf<Int>()
    var published: MlnFfiMapFrameProjection? = null
    val asyncHost =
      object : MlnFfiMapHost by host {
        override val supportsAsyncFrames = true

        override fun enqueueRenderer(action: () -> Unit): Boolean {
          queued.addLast(action)
          return true
        }
      }
    val projecting =
      object : MlnFfiMapRenderer by renderer {
        override fun render(
          host: MlnFfiMapHostSession,
          frame: MlnFfiMapFrame,
          captureProjection: Boolean,
        ): MlnFfiFrameResult {
          renderer.render(host, frame, captureProjection)
          val id = renderer.renderedFrames
          return MlnFfiFrameResult.Rendered(
            RecordingProjection(frame.target.extent, id) { closedProjections += id }
          )
        }

        override fun presentFrame(
          projection: MlnFfiMapFrameProjection?,
          destination: MlnFfiMapDestination,
          scaleFactor: Double,
        ) {
          published = projection
        }

        override fun onSurfaceLost(session: MlnFfiMapHostSession) {
          while (queued.isNotEmpty()) queued.removeFirst()()
          renderer.onSurfaceLost(session)
        }
      }
    val controller =
      MlnFfiSurfaceController(projecting, MlnFfiMapHostResult.Created(asyncHost), null)
    val extent = MapExtent.fromLogical(64, 64, 1.0)
    controller.attach {}
    queued.removeFirst()()
    assertFalse(controller.prepare(extent))
    repeat(10) { assertFalse(controller.prepare(extent)) }
    assertEquals(1, host.acquiredFrames)
    queued.removeFirst()()
    assertTrue(controller.prepare(extent))
    controller.present(extent)
    assertEquals(1, (published as RecordingProjection).id)
    assertTrue(queued.isEmpty(), "Publishing a completion must not start an idle render loop")

    repeat(10) { renderer.requestFrame() }
    assertFalse(controller.prepare(extent))
    repeat(10) { assertFalse(controller.prepare(extent)) }
    assertEquals(2, host.acquiredFrames)
    queued.removeFirst()()
    controller.present(extent)
    assertEquals(1, (published as RecordingProjection).id)
    assertTrue(closedProjections.isEmpty())
    assertTrue(controller.prepare(extent))
    controller.present(extent)
    assertEquals(2, (published as RecordingProjection).id)
    assertEquals(listOf(1), closedProjections)

    renderer.requestFrame()
    assertFalse(controller.prepare(extent))
    controller.close()
    assertEquals(listOf(1, 2, 3), closedProjections)
    assertTrue(host.leakedFrames.isEmpty())
    assertTrue(host.closed)
  }

  @Test
  fun surface_attachment_runs_on_the_host_renderer() {
    val renderer = RecordingRenderer()
    val host = FakeMlnFfiMapHost()
    val queued = ArrayDeque<() -> Unit>()
    val queuedHost =
      object : MlnFfiMapHost by host {
        override fun enqueueRenderer(action: () -> Unit): Boolean {
          queued.addLast(action)
          return true
        }
      }
    val controller =
      MlnFfiSurfaceController(renderer, MlnFfiMapHostResult.Created(queuedHost), null)
    controller.attach {}
    assertTrue(renderer.lifecycle.isEmpty())
    queued.removeFirst()()
    assertEquals(listOf("onSurfaceAvailable"), renderer.lifecycle)
    controller.close()
    assertTrue(host.closed)
  }

  @Test
  fun failed_renderer_release_retains_the_host_target() {
    val renderer = RecordingRenderer(failingSurfaceLosses = 1)
    val host = FakeMlnFfiMapHost()
    val controller = MlnFfiSurfaceController(renderer, MlnFfiMapHostResult.Created(host), null)
    controller.attach {}
    controller.close()
    assertEquals(1, renderer.surfaceLostCount)
    assertFalse(host.closed)
  }

  @Test
  fun hiding_a_surface_clears_its_projection_without_waiting_for_a_capped_frame() =
    runFfiComposeUiTest {
      val renderer = RecordingRenderer()
      var published = false
      val projectingRenderer =
        object : MlnFfiMapRenderer by renderer {
          override val maximumFps = 1

          override fun render(
            host: MlnFfiMapHostSession,
            frame: MlnFfiMapFrame,
            captureProjection: Boolean,
          ): MlnFfiFrameResult {
            renderer.render(host, frame, captureProjection)
            return MlnFfiFrameResult.Rendered(RecordingProjection(frame.target.extent, 1) {})
          }

          override fun presentFrame(
            projection: MlnFfiMapFrameProjection?,
            destination: MlnFfiMapDestination,
            scaleFactor: Double,
          ) {
            published = projection != null
          }
        }
      val factory = FakeMlnFfiMapHostFactory()
      val result = factory.create(factory.bridges.single())
      val visible = mutableStateOf(true)
      setContent {
        MlnFfiMapSurface(
          projectingRenderer,
          result,
          Modifier.size(64.dp),
          presentFrames = visible.value,
        )
      }
      waitUntil(timeoutMillis = TimeoutMillis) { published }
      runOnIdle { visible.value = false }
      waitForIdle()
      assertFalse(published)
    }

  @Test
  fun an_unsuccessful_draw_does_not_reset_the_recovery_budget() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory()
    val host = (factory.create(factory.bridges.single()) as MlnFfiMapHostResult.Created).host
    var draws = 0
    val failingHost =
      object : MlnFfiMapHost by host {
        override fun draw(
          scope: DrawScope,
          target: MlnFfiRenderTarget,
          destination: MlnFfiMapDestination,
        ): Boolean {
          draws++
          if (draws % 2 == 1) throw MlnFfiRecoverableFrameException("deliberate draw failure", null)
          return false
        }
      }
    val result = MlnFfiMapHostResult.Created(failingHost)
    setContent { MlnFfiMapSurface(renderer, result, Modifier.size(64.dp)) }
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.closeCount == 1 }
    assertEquals(7, draws)
    assertEquals(MaxRecoveryAttempts, renderer.surfaceLostCount)
  }

  @Test
  fun disposal_cancels_a_queued_frame_and_rejects_late_requests() = runFfiComposeUiTest {
    mainClock.autoAdvance = false
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory()
    val result = factory.create(factory.bridges.single())
    val show = mutableStateOf(true)
    setContent { if (show.value) MlnFfiMapSurface(renderer, result, Modifier.size(64.dp)) }
    waitUntil(timeoutMillis = TimeoutMillis) {
      mainClock.advanceTimeByFrame()
      renderer.renderedFrames > 0
    }
    runOnIdle {
      renderer.requestFrame()
      show.value = false
    }
    mainClock.advanceTimeByFrame()
    waitForIdle()
    val host = factory.created.single()
    val acquired = host.acquireCount
    assertTrue(host.closed)
    renderer.requestFrame()
    repeat(3) { mainClock.advanceTimeByFrame() }
    waitForIdle()
    assertEquals(acquired, host.acquireCount)
  }

  @Test
  fun requests_coalesce_and_unrelated_measurement_does_not_acquire_a_target() =
    runFfiComposeUiTest {
      val renderer = RecordingRenderer()
      val factory = FakeMlnFfiMapHostFactory()
      val result = factory.create(factory.bridges.single())
      val measureTick = mutableStateOf(0)
      setContent {
        MlnFfiMapSurface(
          renderer,
          result,
          Modifier.size(64.dp).layout { measurable, constraints ->
            val child =
              measurable.measure(
                constraints.copy(maxWidth = constraints.maxWidth + measureTick.value)
              )
            layout(child.width, child.height) { child.place(0, 0) }
          },
        )
      }
      waitUntil(timeoutMillis = TimeoutMillis) { renderer.renderedFrames > 0 }
      waitForIdle()
      val host = factory.created.single()
      val acquired = host.acquireCount
      measureTick.value++
      waitForIdle()
      assertEquals(acquired, host.acquireCount)
      runOnIdle { repeat(20) { renderer.requestFrame() } }
      waitUntil(timeoutMillis = TimeoutMillis) { host.acquireCount > acquired }
      waitForIdle()
      assertEquals(acquired + 1, host.acquireCount)
    }

  @Test
  fun a_temporarily_unavailable_target_retries_without_an_engine_request() = runFfiComposeUiTest {
    val renderer =
      RecordingRenderer(renderResults = ArrayDeque(listOf(MlnFfiFrameResult.RetryNextFrame)))
    val factory = FakeMlnFfiMapHostFactory()
    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.renderedFrames == 2 }
    waitForIdle()
    assertEquals(2, factory.created.single().acquireCount)
  }

  @Test
  fun requests_during_preparation_produce_one_later_attempt() = runFfiComposeUiTest {
    val renderer = RecordingRenderer(additionalFrameRequests = 1)
    val factory = FakeMlnFfiMapHostFactory()
    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.renderedFrames == 2 }
    waitForIdle()
    assertEquals(2, factory.created.single().acquireCount)
  }

  @Test
  fun failed_completion_closes_both_the_candidate_and_the_retained_projection() =
    runFfiComposeUiTest {
      val renderer = RecordingRenderer()
      val factory = FakeMlnFfiMapHostFactory()
      val host = (factory.create(factory.bridges.single()) as MlnFfiMapHostResult.Created).host
      var failCompletion = false
      var created = 0
      val closed = mutableListOf<Int>()
      val projectingRenderer =
        object : MlnFfiMapRenderer by renderer {
          override fun render(
            host: MlnFfiMapHostSession,
            frame: MlnFfiMapFrame,
            captureProjection: Boolean,
          ): MlnFfiFrameResult {
            val result = renderer.render(host, frame, captureProjection)
            if (result !is MlnFfiFrameResult.Rendered) return result
            val id = ++created
            return MlnFfiFrameResult.Rendered(
              RecordingProjection(frame.target.extent, id) { closed += id }
            )
          }
        }
      val failingHost =
        object : MlnFfiMapHost by host {
          override fun completeProducerAccess(frame: MlnFfiMapFrame) {
            check(!failCompletion) { "deliberate completion failure" }
            host.completeProducerAccess(frame)
          }
        }
      val show = mutableStateOf(true)
      val result = MlnFfiMapHostResult.Created(failingHost)
      setContent {
        if (show.value) MlnFfiMapSurface(projectingRenderer, result, Modifier.size(64.dp))
      }
      waitUntil(timeoutMillis = TimeoutMillis) { created > 0 }
      waitForIdle()
      assertEquals(created - 1, closed.size)
      failCompletion = true
      renderer.requestFrame()
      waitUntil(timeoutMillis = TimeoutMillis) { renderer.closeCount == 1 }
      waitForIdle()
      assertEquals((1..created).toSet(), closed.toSet())
      assertEquals(created, closed.size)
      show.value = false
      waitForIdle()
      assertEquals(created, closed.size)
    }

  @Test
  fun preparation_publishes_before_overlay_placement_and_retains_skipped_projections() =
    runFfiComposeUiTest {
      val renderer = RecordingRenderer()
      val factory = FakeMlnFfiMapHostFactory()
      val host = (factory.create(factory.bridges.single()) as MlnFfiMapHostResult.Created).host
      var placedProjection = 0
      val drawnProjections = mutableListOf<Int>()
      val placementsAtDraw = mutableListOf<Pair<Int, Int>>()
      val projectionCloses = mutableListOf<Int>()
      val published = mutableStateOf(0)
      var nextProjection = 0
      val projectingRenderer =
        object : MlnFfiMapRenderer by renderer {
          override fun render(
            host: MlnFfiMapHostSession,
            frame: MlnFfiMapFrame,
            captureProjection: Boolean,
          ): MlnFfiFrameResult {
            val result = renderer.render(host, frame, captureProjection)
            if (result !is MlnFfiFrameResult.Rendered) return result
            val id = ++nextProjection
            return MlnFfiFrameResult.Rendered(
              RecordingProjection(frame.target.extent, id) { projectionCloses += id }
            )
          }

          override fun presentFrame(
            projection: MlnFfiMapFrameProjection?,
            destination: MlnFfiMapDestination,
            scaleFactor: Double,
          ) {
            published.value = (projection as? RecordingProjection)?.id ?: 0
          }
        }
      val recordingHost =
        object : MlnFfiMapHost by host {
          override fun draw(
            scope: DrawScope,
            target: MlnFfiRenderTarget,
            destination: MlnFfiMapDestination,
          ): Boolean {
            placementsAtDraw += published.value to placedProjection
            drawnProjections += placedProjection
            return host.draw(scope, target, destination)
          }
        }
      val show = mutableStateOf(true)
      setContent {
        if (show.value)
          Box(Modifier.size(64.dp)) {
            MlnFfiMapSurface(
              projectingRenderer,
              MlnFfiMapHostResult.Created(recordingHost),
              Modifier.size(64.dp),
            )
            Box(
              Modifier.size(64.dp).layout { measurable, constraints ->
                val child = measurable.measure(constraints)
                layout(child.width, child.height) {
                  placedProjection = published.value
                  child.place(0, 0)
                }
              }
            )
          }
      }
      waitUntil(timeoutMillis = TimeoutMillis) { drawnProjections.isNotEmpty() }
      waitForIdle()
      val retained = published.value
      renderer.skipAllRenders = true
      renderer.requestFrame()
      waitUntil(timeoutMillis = TimeoutMillis) { renderer.skippedFrames > 0 }
      waitForIdle()
      assertEquals(retained, published.value)
      assertFalse(retained in projectionCloses)
      renderer.skipAllRenders = false
      renderer.requestFrame()
      waitUntil(timeoutMillis = TimeoutMillis) { published.value > retained }
      waitForIdle()
      assertTrue(retained in projectionCloses)
      show.value = false
      waitForIdle()
      assertEquals((1..nextProjection).toList(), projectionCloses)
      assertTrue(
        placementsAtDraw.all { it.first == it.second },
        "published versus placed: $placementsAtDraw",
      )
    }

  @Test
  fun a_host_creation_failure_closes_the_renderer_once() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
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
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.closeCount == 1 }
    showSurface.value = false
    waitForIdle()
    assertEquals(1, renderer.closeCount)
  }

  @Test
  fun a_host_that_fails_one_acquire_recovers_and_renders_a_later_frame() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory(configureHost = { it.failingAcquires = 1 })

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.renderedFrames > 0 }

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
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory(configureHost = { it.rotateTargetsOnAcquire = true })
    setSurfaceContent(renderer, factory)
    val host = factory.created.single()
    waitUntil(timeoutMillis = TimeoutMillis) { host.drawnTargets.isNotEmpty() }
    waitForIdle()
    val renderedTarget = host.drawnTargets.last()
    val drawsBeforeSkip = host.drawnTargets.size

    renderer.skipAllRenders = true
    renderer.requestFrame()
    waitUntil(timeoutMillis = TimeoutMillis) { host.drawnTargets.size > drawsBeforeSkip }
    waitForIdle()

    val skippedTarget = renderer.renderTargets.last()
    assertNotEquals(renderedTarget, skippedTarget)
    assertEquals(renderer.renderedFrames - renderer.skippedFrames, host.completedFrames)
    assertTrue(host.drawnTargets.drop(drawsBeforeSkip).all { it == renderedTarget })
    assertFalse(skippedTarget in host.drawnTargets)
    assertTrue(host.leakedFrames.isEmpty())
  }

  @Test
  fun resizes_acquire_targets_for_the_current_draw_size() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory()
    val size = mutableStateOf(64.dp)
    val hostResult = factory.create(factory.bridges.single())

    setContent { MlnFfiMapSurface(renderer, hostResult, Modifier.size(size.value)) }
    val host = factory.created.single()
    waitUntil(timeoutMillis = TimeoutMillis) { host.drawRecords.isNotEmpty() }
    for (nextSize in listOf(96.dp, 48.dp, 80.dp)) {
      val drawsBeforeResize = host.drawRecords.size
      renderer.skipNextRender = true
      size.value = nextSize
      waitUntil(timeoutMillis = TimeoutMillis) { host.drawRecords.size > drawsBeforeResize }
      waitForIdle()

      for (draw in host.drawRecords.drop(drawsBeforeResize)) {
        assertEquals(draw.destinationWidth, draw.target.extent.physicalWidth)
        assertEquals(draw.destinationHeight, draw.target.extent.physicalHeight)
      }
    }
  }

  @Test
  fun resize_keeps_presenting_when_the_new_frame_is_skipped() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory()
    val size = mutableStateOf(64.dp)
    val hostResult = factory.create(factory.bridges.single())

    setContent { MlnFfiMapSurface(renderer, hostResult, Modifier.size(size.value)) }
    val host = factory.created.single()
    waitUntil(timeoutMillis = TimeoutMillis) { host.drawRecords.isNotEmpty() }
    val lastRenderedTarget = host.drawRecords.last().target
    val drawsBeforeResize = host.drawRecords.size

    renderer.skipAllRenders = true
    size.value = 257.dp
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.skippedFrames > 0 }
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
      val renderer = RecordingRenderer()
      val factory = FakeMlnFfiMapHostFactory()
      val size = mutableStateOf(64.dp)
      val hostResult = factory.create(factory.bridges.single())
      renderer.presentationAnchorOffsetX = 12
      renderer.presentationAnchorOffsetY = -4

      setContent { MlnFfiMapSurface(renderer, hostResult, Modifier.size(size.value)) }
      val host = factory.created.single()
      waitUntil(timeoutMillis = TimeoutMillis) { host.drawRecords.isNotEmpty() }
      val completedTarget = host.drawRecords.last().target
      val drawsBeforeResize = host.drawRecords.size

      renderer.presentationAnchorOffsetX = 24
      renderer.presentationAnchorOffsetY = 8
      renderer.skipAllRenders = true
      size.value = 96.dp
      waitUntil(timeoutMillis = TimeoutMillis) { renderer.skippedFrames > 0 }
      waitForIdle()

      val resizeDraws = host.drawRecords.drop(drawsBeforeResize)
      assertTrue(resizeDraws.isNotEmpty())
      assertTrue(resizeDraws.all { it.target == completedTarget })
      // The completed frame stays centered, shifted by the anchor's move of 12 pixels on each axis.
      for (draw in resizeDraws) {
        assertEquals((draw.scopeWidth - draw.destinationWidth) / 2 + 12, draw.destinationLeft)
        assertEquals((draw.scopeHeight - draw.destinationHeight) / 2 + 12, draw.destinationTop)
      }
    }

  @Test
  fun render_uses_the_extent_configured_for_the_same_frame() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory()

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.renderedFrames > 0 }

    assertEquals(renderer.renderTargets.first().extent, renderer.surfaceExtentAtRenders.first())
  }

  @Test
  fun extended_not_ready_does_not_consume_recovery() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory =
      FakeMlnFfiMapHostFactory(configureHost = { it.notReadyAcquires = MaxRecoveryAttempts + 20 })

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.renderedFrames > 0 }

    assertEquals(0, renderer.surfaceLostCount)
    assertEquals(MaxRecoveryAttempts + 21, factory.created.single().acquireCount)
    assertEquals(0, renderer.closeCount)
  }

  @Test
  fun not_ready_between_failures_neither_spends_nor_resets_recovery() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory =
      FakeMlnFfiMapHostFactory(
        configureHost = { host ->
          repeat(MaxRecoveryAttempts) {
            host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.Failure
            host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.NotReady
          }
          host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.Failure
        }
      )

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.closeCount == 1 }

    assertEquals(MaxRecoveryAttempts * 2 + 1, factory.created.single().acquireCount)
    assertEquals(MaxRecoveryAttempts, renderer.surfaceLostCount)
  }

  @Test
  fun a_successfully_presented_frame_resets_recovery() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory(configureHost = { it.failingAcquires = 1 })
    setSurfaceContent(renderer, factory)
    val host = factory.created.single()
    waitUntil(timeoutMillis = TimeoutMillis) { host.drawnTargets.isNotEmpty() }
    runOnIdle {
      host.failingAcquires = MaxRecoveryAttempts + 1
      renderer.requestFrame()
    }
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.closeCount == 1 }

    assertEquals(MaxRecoveryAttempts + 1, renderer.surfaceLostCount)
    assertEquals(MaxRecoveryAttempts + 3, host.acquireCount)
  }

  @Test
  fun repeated_device_changes_each_recover_after_presenting() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory()
    setSurfaceContent(renderer, factory)
    val host = factory.created.single()
    waitUntil(timeoutMillis = TimeoutMillis) { host.drawnTargets.isNotEmpty() }

    // Desktop hosts fail one acquire per graphics-device change; each rebuild presents again.
    repeat(MaxRecoveryAttempts * 2) { change ->
      val before = runOnIdle {
        host.failingAcquires = 1
        renderer.requestFrame()
        host.drawnTargets.last()
      }
      waitUntil(timeoutMillis = TimeoutMillis) {
        renderer.surfaceLostCount == change + 1 && host.drawnTargets.last() !== before
      }
    }

    assertEquals(0, renderer.closeCount)
  }

  @Test
  fun a_renderer_that_fails_one_frame_recovers() = runFfiComposeUiTest {
    val renderer = RecordingRenderer(failingRenders = 1)
    val factory = FakeMlnFfiMapHostFactory()

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.renderedFrames > 0 }

    assertEquals(1, renderer.surfaceLostCount)
    assertEquals(0, renderer.closeCount)
  }

  @Test
  fun a_renderer_that_cannot_release_the_lost_surface_stops_recovery() = runFfiComposeUiTest {
    val renderer = RecordingRenderer(failingSurfaceLosses = 1)
    val factory = FakeMlnFfiMapHostFactory(configureHost = { it.failingAcquires = 1 })

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.closeCount == 1 }

    assertEquals(1, renderer.surfaceLostCount)
    assertEquals(1, factory.created.single().acquireCount)
    assertEquals(listOf("onSurfaceAvailable", "onSurfaceLost"), renderer.lifecycle)
  }

  @Test
  fun repeated_acquire_failure_stops_at_the_bound() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory(configureHost = { it.failingAcquires = Int.MAX_VALUE })

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.closeCount == 1 }

    val host = factory.created.single()
    assertEquals(MaxRecoveryAttempts + 1, host.acquireCount)
    assertEquals(MaxRecoveryAttempts, renderer.surfaceLostCount)
    waitForIdle()
    assertEquals(MaxRecoveryAttempts + 1, host.acquireCount)
    assertEquals(1, renderer.closeCount)
  }

  @Test
  fun unexpected_renderer_failure_stops_without_retrying() = runFfiComposeUiTest {
    val renderer = RecordingRenderer(failingRenders = 1, unexpectedFailure = true)
    val factory = FakeMlnFfiMapHostFactory()

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.closeCount == 1 }

    assertEquals(0, renderer.surfaceLostCount)
    assertEquals(1, factory.created.single().acquireCount)
  }

  @Test
  fun unexpected_host_failure_stops_without_retrying() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory =
      FakeMlnFfiMapHostFactory(
        configureHost = { host ->
          host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.UnexpectedFailure
        }
      )

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.closeCount == 1 }

    assertEquals(0, renderer.surfaceLostCount)
    assertEquals(1, factory.created.single().acquireCount)
  }

  @Test
  fun resize_failure_closes_the_renderer_once() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory()
    val size = mutableStateOf(64.dp)
    val hostResult = factory.create(factory.bridges.single())

    setContent { MlnFfiMapSurface(renderer, hostResult, Modifier.size(size.value)) }
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.renderedFrames > 0 }
    renderer.failingSurfaceChanges = 1
    size.value = 96.dp
    waitUntil(timeoutMillis = TimeoutMillis) { renderer.closeCount == 1 }
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
        logger = MapLog,
      )
    }
  }

  private class RecordingProjection(
    override val extent: MapExtent,
    val id: Int,
    private val onClose: () -> Unit,
  ) : MlnFfiMapFrameProjection {
    override val anchor = extent.centerPresentationAnchor()

    override fun screenLocation(position: org.maplibre.spatialk.geojson.Position) =
      androidx.compose.ui.unit.DpOffset.Zero

    override fun close() {
      onClose()
    }
  }

  private class RecordingRenderer(
    private var failingRenders: Int = 0,
    private val unexpectedFailure: Boolean = false,
    private val requestAnotherFrame: Boolean = false,
    private val renderResults: ArrayDeque<MlnFfiFrameResult> = ArrayDeque(),
    private var additionalFrameRequests: Int = 0,
    private var failingSurfaceLosses: Int = 0,
  ) : MlnFfiMapRenderer {
    override val backend: MapRenderBackend = MapRenderBackend.Vulkan
    val lifecycle: MutableList<String> = mutableListOf()
    val renderTargets: MutableList<MlnFfiRenderTarget> = mutableListOf()
    val surfaceChanges: MutableList<MapExtent> = mutableListOf()
    val surfaceExtentAtRenders: MutableList<MapExtent?> = mutableListOf()
    var renderedFrames = 0
      private set

    var surfaceLostCount = 0
      private set

    var closeCount = 0
      private set

    var failingSurfaceChanges = 0
    var skipNextRender = false
    var skipAllRenders = false
    var presentationAnchorOffsetX = 0
    var presentationAnchorOffsetY = 0
    var skippedFrames = 0
      private set

    private var hostSession: MlnFfiMapHostSession? = null

    fun requestFrame() {
      hostSession?.requestFrame()
    }

    override fun onSurfaceChanged(extent: MapExtent) {
      if (failingSurfaceChanges > 0) {
        failingSurfaceChanges--
        throw IllegalStateException("cannot resize to ${extent.width}x${extent.height}")
      }
      surfaceChanges += extent
    }

    override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
      lifecycle += "onSurfaceAvailable"
      hostSession = session
    }

    override fun onSurfaceLost(session: MlnFfiMapHostSession) {
      surfaceLostCount++
      lifecycle += "onSurfaceLost"
      if (failingSurfaceLosses > 0) {
        failingSurfaceLosses--
        throw IllegalStateException("deliberate surface loss failure")
      }
    }

    override fun render(
      host: MlnFfiMapHostSession,
      frame: MlnFfiMapFrame,
      captureProjection: Boolean,
    ): MlnFfiFrameResult {
      if (failingRenders > 0) {
        failingRenders--
        val error = "renderer lost its device on generation ${frame.target.generation}"
        throw if (unexpectedFailure) IllegalStateException(error)
        else MlnFfiRecoverableFrameException(error, null)
      }
      renderedFrames++
      renderTargets += frame.target
      surfaceExtentAtRenders += surfaceChanges.lastOrNull()
      if (requestAnotherFrame || additionalFrameRequests > 0) {
        if (additionalFrameRequests > 0) additionalFrameRequests--
        hostSession?.requestFrame()
      }
      if (skipNextRender || skipAllRenders) {
        skipNextRender = false
        skippedFrames++
        return MlnFfiFrameResult.AwaitUpdate
      }
      return renderResults.removeFirstOrNull() ?: MlnFfiFrameResult.Rendered()
    }

    override fun presentationAnchor(extent: MapExtent): MlnFfiMapPresentationAnchor {
      val center = extent.centerPresentationAnchor()
      return MlnFfiMapPresentationAnchor(
        x = center.x + presentationAnchorOffsetX,
        y = center.y + presentationAnchorOffsetY,
      )
    }

    override fun close() {
      closeCount++
    }
  }

  private companion object {
    const val MaxRecoveryAttempts = 3
    const val TimeoutMillis = 10_000L
  }
}
