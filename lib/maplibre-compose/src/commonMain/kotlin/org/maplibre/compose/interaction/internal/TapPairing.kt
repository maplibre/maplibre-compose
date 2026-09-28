package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pairs presses and delays a touch click while a second tap could still use it. */
internal class TapPairing(
  private val scope: CoroutineScope,
  private val minimumGapMillis: Long,
  private val timeoutMillis: Long,
  private val click: (GesturePointerSample, Long) -> Unit,
) {
  enum class Press {
    First,
    Bounce,
    Paired,
  }

  /** [doubleTap] is true when a paired press and the first tap can both double-tap. */
  data class Pairing(val role: Press, val doubleTap: Boolean)

  private class Pending(
    val sample: GesturePointerSample,
    val generation: Long,
    val origin: Offset,
    val type: PointerType,
    val doubleTap: Boolean,
    val clickOnExpiry: Boolean,
  ) {
    var claimed = false
    var job: Job? = null
  }

  private var pending: Pending? = null

  /**
   * A press pairs for a double tap only if both taps can double-tap. A quick zoom needs only the
   * second press to match.
   */
  fun press(
    origin: Offset,
    timeMillis: Long,
    type: PointerType,
    slop: Float,
    doubleTap: Boolean,
    quickZoom: Boolean,
  ): Pairing {
    val first = pending
    val elapsed = timeMillis - (first?.sample?.uptimeMillis ?: timeMillis)
    val bothDoubleTap = doubleTap && first?.doubleTap == true
    val role =
      when {
        first == null ||
          first.claimed ||
          !(quickZoom || bothDoubleTap) ||
          first.type != type ||
          (origin - first.origin).getDistance() > slop -> Press.First
        elapsed < minimumGapMillis -> Press.Bounce
        elapsed <= timeoutMillis -> Press.Paired
        else -> Press.First
      }
    when (role) {
      Press.First -> discard(emitClick = true)
      Press.Paired -> {
        checkNotNull(first).claimed = true
        first.job?.cancel()
        first.job = null
      }
      Press.Bounce -> Unit
    }
    return Pairing(role, role == Press.Paired && bothDoubleTap)
  }

  /** Mouse clicks have already been delivered; only touch clicks need a delayed delivery. */
  fun remember(
    sample: GesturePointerSample,
    generation: Long,
    origin: Offset,
    type: PointerType,
    doubleTap: Boolean,
    quickZoom: Boolean,
    clickOnExpiry: Boolean,
  ) {
    discard(emitClick = false)
    if (!doubleTap && !quickZoom) return
    val tap = Pending(sample, generation, origin, type, doubleTap, clickOnExpiry)
    pending = tap
    if (clickOnExpiry)
      tap.job = scope.launch {
        delay(timeoutMillis)
        if (pending === tap && !tap.claimed) {
          pending = null
          click(tap.sample, tap.generation)
        }
      }
  }

  /** A drag may deliver the waiting first click; a double tap or cancellation discards it. */
  fun discard(emitClick: Boolean) {
    val tap = pending ?: return
    pending = null
    tap.job?.cancel()
    if (emitClick && tap.clickOnExpiry) click(tap.sample, tap.generation)
  }
}
