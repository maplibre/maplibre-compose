package org.maplibre.compose.interaction.internal

import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.internal.CameraInputTarget

/**
 * Holds the camera session of the current input burst. A session that loses camera authority calls
 * [onCancelled] only while it is still current, so a stale session cannot cancel a newer burst.
 */
internal class InputBurst(
  private val scope: CoroutineScope,
  private val target: CameraInputTarget,
  private val onCancelled: () -> Unit,
) {
  var session: GestureInputSession? = null
    private set

  private var idleEnd: Job? = null

  fun start(): GestureInputSession {
    lateinit var created: GestureInputSession
    created =
      GestureInputSession(scope, target) {
        if (session === created) onCancelled()
      }
    session = created
    return created
  }

  /**
   * Ends [current] after [idle] unless the next sample restarts the timeout first. Input without a
   * release, such as a wheel or a rotary crown, uses this to end a burst.
   */
  fun endAfterIdle(current: GestureInputSession, idle: Duration) {
    idleEnd?.cancel()
    idleEnd =
      current.scope.launch {
        delay(idle.inWholeMilliseconds)
        session = null
        idleEnd = null
        current.end()
      }
  }

  fun end() {
    idleEnd?.cancel()
    idleEnd = null
    val completed = session ?: return
    session = null
    completed.end()
  }

  fun cancel() {
    idleEnd?.cancel()
    idleEnd = null
    val previous = session ?: return
    session = null
    previous.cancel()
  }
}
