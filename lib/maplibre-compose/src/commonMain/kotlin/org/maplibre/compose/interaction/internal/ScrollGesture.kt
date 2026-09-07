package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.interaction.CameraInputOrigin
import org.maplibre.compose.interaction.MapInteractions

/** Scroll shares the pointer arena so it sees consumption before claiming an event. */
internal class ScrollGesture(
  private val target: CameraInputTarget,
  private val options: MapInteractions,
  private val density: Density,
  private val viewportSize: () -> IntSize,
  private val scrollConverter: ScrollConverter,
  private val scope: CoroutineScope,
) {
  private class Burst(
    val response: ScrollResponse,
    val session: GestureInputSession,
  ) {
    val token
      get() = session.token
  }

  private var burst: Burst? = null
  private var finishJob: Job? = null

  fun onPointerEvent(event: PointerEvent, takeOverContacts: () -> Unit) {
    if (event.changes.any { it.isConsumed }) {
      cancel()
      return
    }

    val normalized =
      normalizeScroll(scrollConverter(event, density, viewportSize()), density) ?: return
    val sample = event.gestureSample(null, density)
    val previous = burst
    if (previous != null && !previous.token.acceptsCommands) {
      cancel()
    }

    val selected =
      options.bindings.scroll.select(sample, options.camera.settings)?.takeUnless {
        it == ScrollResponse.None
      }
    if (burst != null && burst?.response != selected) cancel()
    if (selected == null) return
    if (selected == ScrollResponse.Zoom && normalized.y.value == 0f) return

    target.observeInput()
    val current =
      burst
        ?: run {
          takeOverContacts()
          lateinit var session: GestureInputSession
          session =
            GestureInputSession(scope, target, origin = CameraInputOrigin.Scroll) {
              if (burst?.session === session) cancel()
            }
          Burst(selected, session).also { burst = it }
        }

    if (!current.token.acceptsCommands) {
      cancel()
      return
    }

    when (selected) {
      ScrollResponse.Pan ->
        target.inputPanBy(
          normalized.x.value.toDouble(),
          normalized.y.value.toDouble(),
          gestureToken = current.token,
        )
      ScrollResponse.Zoom -> {
        val scale =
          zoomLevelsToScale(normalized.y.value.toDouble() * options.bindings.scroll.zoomPerDp)
        if (scale.isFinite() && scale > 0.0)
          target.inputScaleBy(
            scale,
            options.bindings.scroll.anchor.location(sample),
            gestureToken = current.token,
          )
      }
      else -> Unit
    }

    event.changes.forEach { it.consume() }

    // Wheel events have no release. The idle timeout closes one burst without adding momentum;
    // the host may already include its own inertial scrolling.
    finishJob?.cancel()
    finishJob =
      current.session.scope.launch {
        delay(options.bindings.scroll.idleDuration.inWholeMilliseconds)
        burst = null
        finishJob = null
        current.session.end()
      }
  }

  fun cancel() {
    finishJob?.cancel()
    finishJob = null
    val previous = burst ?: return
    burst = null
    previous.session.cancel()
  }
}
