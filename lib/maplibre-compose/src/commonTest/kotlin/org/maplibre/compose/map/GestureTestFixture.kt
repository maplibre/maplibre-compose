package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import kotlin.time.Duration
import org.maplibre.compose.camera.internal.BoxZoomFit
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.internal.ClickPath
import org.maplibre.compose.interaction.internal.TapFamily
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/** Records input responses while using the production attachment and camera authority. */
internal class GestureTestFixture : AutoCloseable {
  private val runtime = mapRuntimeForTest()
  val state = runtime.createMapState(BaseStyle.Empty)
  val target = RecordingGestureTarget(state)

  override fun close() {
    state.close()
    target.drain()
    runtime.close()
  }
}

/** Only engine execution is simulated. Pausing it lets ownership tests control queue ordering. */
internal class RecordingGestureTarget(
  private val state: MapState,
  private val deferred: Boolean = false,
) : PresentationTestAdapter(), CameraInputTarget {
  var startedCount = 0
    private set

  var endedCount = 0
    private set

  val moveCalls = mutableListOf<Offset>()
  val scaleCalls = mutableListOf<ScaleCall>()
  val rotateCalls = mutableListOf<RotateCall>()
  val fitCalls = mutableListOf<Pair<BoxZoomFit, Duration>>()
  var project: (DpOffset) -> Position? = { null }
  private val pending = ArrayDeque<() -> Unit>()

  init {
    currentViewport = viewportFor(MapSnapshotRequest(100, 100))
    state.publishPresentation(state.reservePresentation(), this)
    state.synchronizeCamera(this)
  }

  fun updateConfiguration(options: MapInteractions) {
    state.gestureAuthority.updateConfiguration(options.camera)
  }

  override val isGestureReady: Boolean
    get() = !state.isClosed && currentViewport != null

  override fun interruptCamera() {
    state.gestureAuthority.beginProgrammatic()
  }

  override fun positionFromScreenLocation(offset: DpOffset): Position? = project(offset)

  override fun observeInput(): Long = state.gestureAuthority.observeInput()

  override val inputGeneration: Long
    get() = state.gestureAuthority.generation

  override fun onGestureStartedIfCurrent(generation: Long): CameraInputToken? =
    state.gestureAuthority.acquireIfCurrent(this, generation)?.also { startedCount++ }

  override fun onGestureStarted(): CameraInputToken =
    state.gestureAuthority.acquire(this).also { startedCount++ }

  override fun onGestureEnded(token: CameraInputToken) = finish(token, cancelled = false)

  override fun cancelGesture(token: CameraInputToken) = finish(token, cancelled = true)

  private fun finish(token: CameraInputToken, cancelled: Boolean) {
    token.finish(cancelled) {
      execute {
        endedCount++
        token.complete()
      }
    }
  }

  override suspend fun awaitGestureEnded(token: CameraInputToken) = token.completion.await()

  private fun execute(action: () -> Unit) {
    if (deferred) pending.add(action) else action()
  }

  private fun command(token: CameraInputToken?, action: () -> Unit) {
    checkNotNull(token).enqueue { execute { if (token.canExecute) action() } }
  }

  fun drain() {
    while (pending.isNotEmpty()) pending.removeFirst().invoke()
  }

  override fun moveBy(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: CameraInputToken?,
  ) = command(gestureToken) { moveCalls += Offset(deltaX.toFloat(), deltaY.toFloat()) }

  override fun scaleBy(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: CameraInputToken?,
  ) = command(gestureToken) { scaleCalls += ScaleCall(scale, anchor) }

  override fun rotateAndPitchBy(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    anchor: DpOffset?,
    gestureToken: CameraInputToken?,
  ) = command(gestureToken) { rotateCalls += RotateCall(bearingDelta, pitchDelta, anchor) }

  override suspend fun fitBoundsAwaitingTransition(
    fit: BoxZoomFit,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) = command(gestureToken) { fitCalls += fit to duration }

  override suspend fun moveByAwaitingTransition(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) = moveBy(deltaX, deltaY, duration, gestureToken)

  override suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) = scaleBy(scale, anchor, duration, gestureToken)

  override suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    gestureToken: CameraInputToken,
    anchor: DpOffset?,
  ) = rotateAndPitchBy(bearingDelta, pitchDelta, duration, anchor, gestureToken)

  var clickFamilies = setOf(TapFamily.Tap, TapFamily.LongPress, TapFamily.SecondaryClick)
  val deliveredTapFamilies = mutableListOf<TapFamily>()
  var clicks = 0
  var longClicks = 0

  fun capture(family: TapFamily): ClickPath =
    ClickPath({ !state.isClosed }) {
      deliveredTapFamilies += family
      when (family) {
        TapFamily.Tap -> clicks++
        TapFamily.LongPress,
        TapFamily.SecondaryClick -> longClicks++
        else -> Unit
      }
      ClickResult.Pass
    }

  data class ScaleCall(val scale: Double, val anchor: DpOffset?)

  data class RotateCall(val bearingDelta: Double, val pitchDelta: Double, val anchor: DpOffset?)
}
