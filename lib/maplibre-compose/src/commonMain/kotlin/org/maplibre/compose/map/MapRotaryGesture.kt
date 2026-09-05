package org.maplibre.compose.map

import androidx.compose.ui.input.rotary.RotaryScrollEvent
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Focused rotary input has its own burst; it does not resume a pointer's continuation. */
internal class MapRotaryGesture(
  private val target: GestureTarget,
  private val binding: () -> RotaryZoomBinding,
  private val ids: GestureIds,
  private val notchPixels: Float,
  private val scope: CoroutineScope,
  private val continuation: GestureContinuation,
) {
  private var session: GestureInputSession? = null
  private var gestureId = 0L
  private var finishJob: Job? = null

  fun onEvent(event: RotaryScrollEvent): Boolean =
    onSample(event.verticalScrollPixels, event.horizontalScrollPixels, event.uptimeMillis)

  fun onSample(
    verticalScrollPixels: Float,
    horizontalScrollPixels: Float,
    uptimeMillis: Long,
  ): Boolean {
    val selected = binding()
    if (
      !selected.enabled ||
        notchPixels <= 0f ||
        !notchPixels.isFinite() ||
        verticalScrollPixels == 0f ||
        !verticalScrollPixels.isFinite() ||
        !horizontalScrollPixels.isFinite()
    )
      return false
    val scale = 2.0.pow(-verticalScrollPixels / notchPixels * selected.zoomStep)
    if (!scale.isFinite() || scale <= 0.0) return false
    target.observeInput()
    val current =
      session?.takeIf { it.token.acceptsCommands }
        ?: run {
          cancel()
          continuation.finish(target::cancelGesture)
          gestureId = ids.next()
          lateinit var created: GestureInputSession
          created = GestureInputSession(scope, target) { if (session === created) cancel() }
          created.also { session = it }
        }
    try {
      selected.onEvent?.invoke(
        RotaryGestureEvent(
          gestureId,
          uptimeMillis,
          verticalScrollPixels,
          horizontalScrollPixels,
        )
      )
      if (!current.token.acceptsCommands) {
        cancel()
        return true
      }
      target.scaleBy(scale, null, gestureToken = current.token)
      finishJob?.cancel()
      finishJob =
        current.scope.launch {
          delay(selected.idleDuration.inWholeMilliseconds)
          session = null
          finishJob = null
          current.end()
        }
    } catch (error: Throwable) {
      cancel()
      throw error
    }
    return true
  }

  fun cancel() {
    finishJob?.cancel()
    finishJob = null
    val previous = session
    session = null
    previous?.cancel()
  }
}
