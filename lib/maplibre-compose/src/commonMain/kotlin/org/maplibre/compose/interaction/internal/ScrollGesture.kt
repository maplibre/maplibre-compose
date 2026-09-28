package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.interaction.ScrollResponse

/** Scroll shares the pointer arena so it sees consumption before claiming an event. */
internal class ScrollGesture(
  private val target: CameraInputTarget,
  private val options: InputConfiguration,
  private val density: Density,
  private val viewportSize: () -> IntSize,
  private val scrollConverter: ScrollConverter,
  scope: CoroutineScope,
) {
  private val burst = InputBurst(scope, target) { cancel() }
  /**
   * The response the current burst started with; meaningful only while `burst.session` is non-null.
   */
  private var response: ScrollResponse? = null

  fun onPointerEvent(event: PointerEvent, takeOverContacts: () -> Unit) {
    if (event.changes.any { it.isConsumed }) {
      cancel()
      return
    }

    val normalized =
      normalizeScroll(scrollConverter(event, density, viewportSize()), density) ?: return
    val sample = event.gestureSample(null, density)
    if (burst.session?.token?.acceptsCommands == false) cancel()

    val selected =
      options.bindings.scroll.select(sample, options.camera.settings)?.takeUnless {
        it == ScrollResponse.None
      }
    if (burst.session != null && response != selected) cancel()
    if (selected == null) return
    if (selected == ScrollResponse.Zoom && normalized.y.value == 0f) return

    target.observeInput()
    val session =
      burst.session
        ?: run {
          takeOverContacts()
          response = selected
          burst.start()
        }

    if (!session.token.acceptsCommands) {
      cancel()
      return
    }

    when (selected) {
      ScrollResponse.Pan ->
        target.inputPanBy(
          normalized.x.value.toDouble(),
          normalized.y.value.toDouble(),
          gestureToken = session.token,
        )
      ScrollResponse.Zoom -> {
        val scale =
          zoomLevelsToScale(normalized.y.value.toDouble() * options.bindings.scroll.zoomPerDp)
        if (scale.isFinite() && scale > 0.0)
          target.inputScaleBy(
            scale,
            options.bindings.scroll.anchor.location(sample),
            gestureToken = session.token,
          )
      }
      else -> Unit
    }

    event.changes.forEach { it.consume() }

    // Wheel events have no release. The idle timeout closes one burst without adding momentum;
    // the host may already include its own inertial scrolling.
    burst.endAfterIdle(session, options.bindings.scroll.idleDuration)
  }

  fun cancel() {
    burst.cancel()
  }
}
