package org.maplibre.compose.style

import kotlinx.coroutines.CompletableDeferred

/**
 * Delegates to [inner], but while [ownerBusy] is set queues each [awaitOwner] task the way a busy
 * native owner thread does. [runOwnerTasks] runs the tasks queued so far, in order.
 */
internal open class QueuedOwnerStyleBinding(private val inner: StyleBinding) :
  StyleBinding by inner {
  private val queued = ArrayDeque<CompletableDeferred<Unit>>()

  var ownerBusy = false

  val ownerTasks: Int
    get() = queued.size

  override suspend fun <T> awaitOwner(action: () -> T): T? {
    if (ownerBusy) {
      val turn = CompletableDeferred<Unit>()
      queued.addLast(turn)
      turn.await()
    }
    return inner.awaitOwner(action)
  }

  fun runOwnerTasks() {
    val tasks = queued.toList()
    queued.clear()
    tasks.forEach { it.complete(Unit) }
  }
}
