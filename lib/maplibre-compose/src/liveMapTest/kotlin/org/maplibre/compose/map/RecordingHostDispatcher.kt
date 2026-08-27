package org.maplibre.compose.map

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.compose.style.StyleHostDispatcher
import org.maplibre.compose.style.styleHostDispatcher

/**
 * A [StyleHostDispatcher] over [dispatcher] that records the host's close.
 *
 * The host releases its dispatcher last in its teardown, so [closedState] reports that the teardown
 * finished.
 */
internal class RecordingHostDispatcher(
  override val dispatcher: CoroutineDispatcher,
  private val onClose: () -> Unit = {},
) : StyleHostDispatcher {

  private val closedFlow = MutableStateFlow(false)

  /** True once the host has released this dispatcher. */
  val closedState: StateFlow<Boolean> = closedFlow

  val closed: Boolean
    get() = closedFlow.value

  override fun close() {
    closedFlow.value = true
    onClose()
  }
}

/** Wraps the platform's real host dispatcher, so a test observes a real host teardown. */
internal fun recordingHostDispatcher(): RecordingHostDispatcher {
  val real = styleHostDispatcher()
  return RecordingHostDispatcher(real.dispatcher, real::close)
}
