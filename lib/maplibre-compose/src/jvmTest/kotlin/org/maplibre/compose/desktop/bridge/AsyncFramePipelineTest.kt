package org.maplibre.compose.desktop.bridge

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.VulkanContextHandles
import org.maplibre.compose.mlnffi.VulkanImageTarget

class AsyncFramePipelineTest {
  @Test
  fun capacity_and_completion_preserve_the_displayed_render() {
    val dispatcher = TestDispatcher()
    val released = mutableListOf<Long>()
    val pipeline = pipeline(dispatcher, released)
    pipeline.replaceActiveGenerations(listOf(1, 2, 3))
    var requests = 0

    assertTrue(pipeline.submit(frame(1), { MlnFfiFrameResult.RENDERED }) { requests++ })
    assertTrue(pipeline.submit(frame(2), { MlnFfiFrameResult.SKIPPED }) { requests++ })
    assertFalse(pipeline.submit(frame(3), { MlnFfiFrameResult.RENDERED }) { requests++ })
    assertEquals(0, requests)

    dispatcher.runNext()
    assertEquals(1, requests)
    val renderedCompletion = assertIs<AsyncFrameCompletion>(pipeline.collectCompleted())
    val rendered = renderedCompletion.production
    assertEquals(MlnFfiFrameResult.RENDERED, rendered.result)
    assertTrue(renderedCompletion.shouldSubmitSuccessor)
    assertEquals(1, pipeline.displayedGeneration)

    dispatcher.runNext()
    val skippedCompletion = assertIs<AsyncFrameCompletion>(pipeline.collectCompleted())
    val skipped = skippedCompletion.production
    assertEquals(MlnFfiFrameResult.SKIPPED, skipped.result)
    assertFalse(skippedCompletion.shouldSubmitSuccessor)
    assertEquals(1, pipeline.displayedGeneration)
    assertEquals(listOf(1L, 2L), released)
    assertTrue(pipeline.submit(frame(3), { MlnFfiFrameResult.RENDERED }) {})
  }

  @Test
  fun rendered_then_skipped_publishes_the_render_and_stops_the_successor() {
    val dispatcher = TestDispatcher()
    val released = mutableListOf<Long>()
    val pipeline = pipeline(dispatcher, released)
    pipeline.replaceActiveGenerations(listOf(1, 2, 3))
    assertTrue(pipeline.submit(frame(1), { MlnFfiFrameResult.RENDERED }) {})
    assertTrue(pipeline.submit(frame(2), { MlnFfiFrameResult.SKIPPED }) {})

    dispatcher.runNext()
    dispatcher.runNext()

    val completed = assertIs<AsyncFrameCompletion>(pipeline.collectCompleted())
    assertEquals(MlnFfiFrameResult.RENDERED, completed.production.result)
    assertEquals(1, completed.production.target.generation)
    assertEquals(1, pipeline.displayedGeneration)
    assertFalse(completed.shouldSubmitSuccessor)
    assertEquals(listOf(1L, 2L), released)
  }

  @Test
  fun a_resize_retires_an_in_flight_generation() {
    val dispatcher = TestDispatcher()
    val released = mutableListOf<Long>()
    val pipeline = pipeline(dispatcher, released)
    pipeline.replaceActiveGenerations(listOf(1, 2, 3))
    assertTrue(pipeline.submit(frame(1), { MlnFfiFrameResult.RENDERED }) {})

    pipeline.replaceActiveGenerations(listOf(4, 5, 6))
    dispatcher.runNext()

    assertNull(pipeline.collectCompleted())
    assertNull(pipeline.displayedGeneration)
    assertEquals(listOf(1L), released)
    assertEquals(4, pipeline.freeGeneration())
  }

  @Test
  fun a_worker_failure_is_rethrown_after_releasing_its_frame() {
    val dispatcher = TestDispatcher()
    val released = mutableListOf<Long>()
    val pipeline = pipeline(dispatcher, released)
    pipeline.replaceActiveGenerations(listOf(1))
    assertTrue(pipeline.submit(frame(1), { throw DeliberateFailure() }) {})
    dispatcher.runNext()

    assertFailsWith<DeliberateFailure> { pipeline.collectCompleted() }
    assertEquals(listOf(1L), released)
  }

  @Test
  fun close_waits_for_work_and_releases_its_frame() {
    val started = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val released = mutableListOf<Long>()
    val pipeline =
      AsyncFramePipeline(
        dispatch = { action ->
          thread(isDaemon = true) { action() }
          true
        },
        releaseFrame = { released += it.frameId },
        maxPending = 2,
      )
    pipeline.replaceActiveGenerations(listOf(1))
    assertTrue(
      pipeline.submit(
        frame(1),
        action = {
          started.countDown()
          finish.await()
          MlnFfiFrameResult.RENDERED
        },
        requestFrame = {},
      )
    )
    started.await()

    val closed = CountDownLatch(1)
    val closer =
      thread(isDaemon = true) {
        pipeline.close()
        closed.countDown()
      }
    assertFalse(closed.await(20, java.util.concurrent.TimeUnit.MILLISECONDS))
    finish.countDown()
    closer.join()

    assertEquals(listOf(1L), released)
  }

  private fun pipeline(
    dispatcher: TestDispatcher,
    released: MutableList<Long>,
  ): AsyncFramePipeline =
    AsyncFramePipeline(
      dispatch = dispatcher::dispatch,
      releaseFrame = { released += it.frameId },
      maxPending = 2,
    )

  private fun frame(generation: Long): MlnFfiMapFrame =
    MlnFfiMapFrame(
      frameId = generation,
      extent = EXTENT,
      target =
        VulkanImageTarget(
          context = CONTEXT,
          image = NativeHandle(10 + generation),
          imageView = NativeHandle(20 + generation),
          format = 37,
          initialLayout = 0,
          finalLayout = 1,
          queueFamilyIndex = 0,
          extent = EXTENT,
          generation = generation,
        ),
      presentationTimeNanos = null,
    )

  private class TestDispatcher {
    private val tasks = ArrayDeque<() -> Unit>()

    fun dispatch(action: () -> Unit): Boolean {
      tasks += action
      return true
    }

    fun runNext() {
      tasks.removeFirst()()
    }
  }

  private class DeliberateFailure : RuntimeException()

  private companion object {
    val EXTENT = MapExtent.fromPhysical(64, 64, 1.0)
    val CONTEXT =
      VulkanContextHandles(
        instance = NativeHandle(1),
        physicalDevice = NativeHandle(2),
        device = NativeHandle(3),
        graphicsQueue = NativeHandle(4),
        graphicsQueueFamilyIndex = 0,
        getInstanceProcAddr = NativeHandle(5),
        getDeviceProcAddr = NativeHandle(6),
      )
  }
}
