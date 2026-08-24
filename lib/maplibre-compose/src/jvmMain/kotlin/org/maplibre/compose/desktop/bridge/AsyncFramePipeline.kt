package org.maplibre.compose.desktop.bridge

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameProduction

/**
 * Schedules producer work while the consumer continues drawing completed texture generations.
 *
 * All state belongs to the consumer thread. A worker only executes a task and requests a consumer
 * frame from [FutureTask.done], after the result is available to [collectCompleted].
 */
internal class AsyncFramePipeline(
  private val dispatch: (() -> Unit) -> Boolean,
  private val releaseFrame: (MlnFfiMapFrame) -> Unit,
  private val maxPending: Int,
) : AutoCloseable {
  private class PendingRender(
    val frame: MlnFfiMapFrame,
    val generation: Long,
    val task: FutureTask<MlnFfiFrameResult>,
  )

  private val activeGenerations = linkedSetOf<Long>()
  private val pending = ArrayDeque<PendingRender>()

  var displayedGeneration: Long? = null
    private set

  val hasPending: Boolean
    get() = pending.isNotEmpty()

  /** Replaces the texture generations that may receive and publish new renders. */
  fun replaceActiveGenerations(generations: Collection<Long>) {
    activeGenerations.clear()
    activeGenerations.addAll(generations)
  }

  /** Clears a displayed generation whose consumer context no longer owns usable texture names. */
  fun abandonDisplayedGeneration() {
    displayedGeneration = null
  }

  /** Returns an active generation that is neither displayed nor receiving producer work. */
  fun freeGeneration(): Long? = activeGenerations.firstOrNull { generation ->
    generation != displayedGeneration && pending.none { it.generation == generation }
  }

  /** Returns a generation whose target can represent this draw, even when no slot is free. */
  fun acquisitionGeneration(): Long? = freeGeneration() ?: pending.firstOrNull()?.generation

  /**
   * Collects every completed head task and returns the newest result from an active generation.
   * Retired generations are released without publishing their contents.
   */
  fun collectCompleted(): MlnFfiMapFrameProduction.Completed? {
    var newest: MlnFfiMapFrameProduction.Completed? = null
    while (pending.firstOrNull()?.task?.isDone == true) {
      val completed = pending.removeFirst()
      val result =
        try {
          completed.task.get()
        } catch (error: ExecutionException) {
          throw error.cause ?: error
        } finally {
          releaseFrame(completed.frame)
        }
      if (completed.generation in activeGenerations) {
        if (result == MlnFfiFrameResult.RENDERED) {
          displayedGeneration = completed.generation
        }
        newest = MlnFfiMapFrameProduction.Completed(result, completed.frame.target)
      }
    }
    return newest
  }

  /** Queues [action] when [frame] names a free active generation and capacity remains. */
  fun submit(
    frame: MlnFfiMapFrame,
    action: () -> MlnFfiFrameResult,
    requestFrame: () -> Unit,
  ): Boolean {
    val generation = frame.target.generation
    if (
      pending.size >= maxPending ||
        generation !in activeGenerations ||
        generation == displayedGeneration ||
        pending.any { it.generation == generation }
    ) {
      return false
    }

    val task =
      object : FutureTask<MlnFfiFrameResult>(Callable(action)) {
        override fun done() {
          requestFrame()
        }
      }
    if (!dispatch { task.run() }) return false
    pending.addLast(PendingRender(frame, generation, task))
    return true
  }

  /** Waits for submitted work and releases every frame before returning. */
  override fun close() {
    var failure: Throwable? = null
    while (pending.isNotEmpty()) {
      val render = pending.removeFirst()
      try {
        render.task.get()
      } catch (error: ExecutionException) {
        if (failure == null) failure = error.cause ?: error
      } finally {
        runCatching { releaseFrame(render.frame) }.onFailure { if (failure == null) failure = it }
      }
    }
    failure?.let { throw it }
  }
}
