package org.maplibre.compose.interaction.internal

import kotlin.math.pow
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.interaction.CameraInputOrigin
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.style.scaledBy
import org.maplibre.compose.style.systemAnimatorDurationScale

internal class GestureContinuation(private val scope: CoroutineScope) {
  private var configuration: Any? = null

  fun configure(key: Any, target: CameraInputTarget) {
    if (configuration != null && configuration != key) finish(target::cancelGesture)
    configuration = key
  }

  private var scaleVelocityJob: Job? = null
  private var rotationVelocityJob: Job? = null
  private var flingJob: Job? = null
  private var boundsFitJob: Job? = null
  private var discreteSession: GestureInputSession? = null
  private var finishJob: Job? = null
  private var openToken: CameraInputToken? = null

  fun launchScale(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
    scaleVelocityJob?.cancel()
    scaleVelocityJob = scope.launch(block = block)
  }

  fun launchRotation(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
    rotationVelocityJob?.cancel()
    rotationVelocityJob = scope.launch(block = block)
  }

  fun launchFling(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
    flingJob?.cancel()
    flingJob = scope.launch(block = block)
  }

  fun launchBoundsFit(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
    boundsFitJob?.cancel()
    boundsFitJob = scope.launch(block = block)
  }

  fun hasMotionJobs(): Boolean =
    boundsFitJob?.isActive == true ||
      flingJob?.isActive == true ||
      scaleVelocityJob?.isActive == true ||
      rotationVelocityJob?.isActive == true

  /**
   * Ends [token] when every motion job finishes on its own. Cancelled work belongs to a revoked
   * session, whose cancellation path closes the token.
   */
  fun finishWhenMotionJobsComplete(
    scope: CoroutineScope,
    token: CameraInputToken,
    onFinished: (CameraInputToken) -> Unit,
  ) {
    finishJob?.cancel()
    openToken = token
    finishJob = scope.launch {
      val jobs = listOfNotNull(flingJob, scaleVelocityJob, rotationVelocityJob, boundsFitJob)
      jobs.joinAll()
      if (jobs.any { it.isCancelled }) return@launch
      finishJob = null
      openToken = null
      onFinished(token)
    }
  }

  /** A delayed tap may acquire the camera only while its captured input generation is current. */
  fun launchTapTransition(
    target: CameraInputTarget,
    generation: Long,
    command: suspend CameraInputTarget.(CameraInputToken) -> Unit,
  ) {
    val token = target.onGestureStartedIfCurrent(generation) ?: return
    discreteSession?.cancel()
    val session = GestureInputSession(scope, target, token, origin = CameraInputOrigin.Tap)
    discreteSession = session
    session.scope.launch {
      try {
        command(target, token)
      } finally {
        if (currentCoroutineContext().isActive) session.end() else session.cancel()
      }
    }
  }

  /** Stops this node's earlier response jobs and releases any retained motion token. */
  fun finish(onFinished: (CameraInputToken) -> Unit) {
    scaleVelocityJob?.cancel()
    scaleVelocityJob = null
    rotationVelocityJob?.cancel()
    rotationVelocityJob = null
    flingJob?.cancel()
    flingJob = null
    boundsFitJob?.cancel()
    boundsFitJob = null
    discreteSession?.cancel()
    discreteSession = null
    val token = openToken
    finishJob?.cancel()
    finishJob = null
    openToken = null
    token?.takeIf { it.acceptsCommands }?.let(onFinished)
  }
}

/** A zoom level is a doubling. */
internal fun zoomLevelsToScale(levelDelta: Double): Double = 2.0.pow(levelDelta)

internal fun MapInteractions.scaledAnimationDuration(): Duration =
  animationDuration.scaledBy(systemAnimatorDurationScale())
