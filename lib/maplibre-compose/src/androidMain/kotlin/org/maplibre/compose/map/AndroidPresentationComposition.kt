package org.maplibre.compose.map

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * A persistent Compose Runtime composition with no UI host. [scope] must carry a main-thread
 * dispatcher and a frame clock; the recomposer and every effect run in it.
 */
internal class AndroidPresentationComposition(
  scope: CoroutineScope,
  content: @Composable () -> Unit,
) : AutoCloseable {
  private val handler = Handler(Looper.getMainLooper())
  private val notificationPending = AtomicBoolean(false)
  private val applyNotifications = Runnable {
    notificationPending.set(false)
    Snapshot.sendApplyNotifications()
  }
  // Compose UI sends apply notifications for the process only while a UI composition exists.
  private val snapshotObserver = Snapshot.registerGlobalWriteObserver {
    scheduleApplyNotifications()
  }
  private val recomposer = Recomposer(scope.coroutineContext)
  private val composition = Composition(PresentationApplier(), recomposer)

  init {
    scope.launch(start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
    try {
      composition.setContent(content)
    } catch (error: Throwable) {
      runCatching { close() }.exceptionOrNull()?.let { if (it !== error) error.addSuppressed(it) }
      throw error
    }
  }

  private fun scheduleApplyNotifications() {
    if (notificationPending.compareAndSet(false, true)) handler.post(applyNotifications)
  }

  override fun close() {
    snapshotObserver.dispose()
    try {
      composition.dispose()
    } finally {
      recomposer.cancel()
      // Deliver the final writes to outside observers after the caller finishes its own teardown.
      scheduleApplyNotifications()
    }
  }
}

private class PresentationApplier : AbstractApplier<Unit>(Unit) {
  override fun insertTopDown(index: Int, instance: Unit): Unit =
    error("A map presentation cannot emit UI")

  override fun insertBottomUp(index: Int, instance: Unit): Unit =
    error("A map presentation cannot emit UI")

  override fun remove(index: Int, count: Int): Unit = error("A map presentation cannot emit UI")

  override fun move(from: Int, to: Int, count: Int): Unit =
    error("A map presentation cannot emit UI")

  override fun onClear() {}
}
