package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.rotary.RotaryScrollEvent
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.interaction.CameraInputOrigin

/** Focused rotary input has its own burst; it does not resume a pointer's continuation. */
internal class RotaryGesture(
  private val target: CameraInputTarget,
  private val binding: RotaryBinding,
  private val notchPixels: Float,
  private val scope: CoroutineScope,
) {
  private var session: GestureInputSession? = null

  private var finishJob: Job? = null

  fun onEvent(event: RotaryScrollEvent): Boolean = onSample(event.verticalScrollPixels)

  fun onSample(verticalScrollPixels: Float): Boolean {
    if (
      !binding.enabled ||
        notchPixels <= 0f ||
        !notchPixels.isFinite() ||
        verticalScrollPixels == 0f ||
        !verticalScrollPixels.isFinite()
    )
      return false
    val scale = 2.0.pow(-verticalScrollPixels / notchPixels * binding.zoomStep)
    if (!scale.isFinite() || scale <= 0.0) return false
    target.observeInput()
    val current =
      session?.takeIf { it.token.acceptsCommands }
        ?: run {
          cancel()

          lateinit var created: GestureInputSession
          created =
            GestureInputSession(scope, target, origin = CameraInputOrigin.Rotary) {
              if (session === created) cancel()
            }
          created.also { session = it }
        }
    try {
      target.inputScaleBy(scale, null, gestureToken = current.token)
      finishJob?.cancel()
      finishJob =
        current.scope.launch {
          delay(binding.idleDuration.inWholeMilliseconds)
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
