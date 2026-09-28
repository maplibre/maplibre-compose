package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.rotary.RotaryScrollEvent
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.inputScaleBy

/** Focused rotary input has its own burst; it does not resume a pointer's continuation. */
internal class RotaryGesture(
  private val target: CameraInputTarget,
  private val binding: RotaryBinding,
  private val notchPixels: Float,
  scope: CoroutineScope,
) {
  private val burst = InputBurst(scope, target) { cancel() }

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
    if (burst.session?.token?.acceptsCommands == false) cancel()

    target.observeInput()
    val session = burst.session ?: burst.start()
    if (!session.token.acceptsCommands) {
      cancel()
      return false
    }

    try {
      target.inputScaleBy(scale, null, gestureToken = session.token)
      burst.endAfterIdle(session, binding.idleDuration)
    } catch (error: Throwable) {
      cancel()
      throw error
    }
    return true
  }

  fun cancel() {
    burst.cancel()
  }
}
