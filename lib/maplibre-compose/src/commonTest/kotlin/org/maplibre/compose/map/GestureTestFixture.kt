package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import kotlin.time.Duration
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
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
) : PresentationTestAdapter(), GestureTarget, MapInteractionTarget {
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

  override fun cancelTransitions() = Unit

  override fun positionFromScreenLocation(offset: DpOffset): Position? = project(offset)

  override fun observeInput(): Long = state.gestureAuthority.observeInput()

  override val inputGeneration: Long
    get() = state.gestureAuthority.generation

  override fun onGestureStartedIfCurrent(generation: Long): GestureToken? =
    state.gestureAuthority.acquireIfCurrent(this, generation)?.also { startedCount++ }

  override fun onGestureStarted(): GestureToken =
    state.gestureAuthority.acquire(this).also { startedCount++ }

  override fun onGestureEnded(token: GestureToken) = finish(token, cancelled = false)

  override fun cancelGesture(token: GestureToken) = finish(token, cancelled = true)

  private fun finish(token: GestureToken, cancelled: Boolean) {
    token.finish(cancelled) {
      execute {
        endedCount++
        token.complete()
      }
    }
  }

  override suspend fun awaitGestureEnded(token: GestureToken) = token.completion.await()

  private fun execute(action: () -> Unit) {
    if (deferred) pending.add(action) else action()
  }

  private fun command(token: GestureToken?, action: () -> Unit) {
    checkNotNull(token).enqueue { execute { if (token.canExecute) action() } }
  }

  fun drain() {
    while (pending.isNotEmpty()) pending.removeFirst().invoke()
  }

  override fun moveBy(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken?,
  ) = command(gestureToken) { moveCalls += Offset(deltaX.toFloat(), deltaY.toFloat()) }

  override fun scaleBy(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken?,
  ) = command(gestureToken) { scaleCalls += ScaleCall(scale, anchor) }

  override fun rotateAndPitchBy(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    anchor: DpOffset?,
    gestureToken: GestureToken?,
  ) = command(gestureToken) { rotateCalls += RotateCall(bearingDelta, pitchDelta, anchor) }

  override suspend fun fitBoundsAwaitingTransition(
    fit: BoxZoomFit,
    duration: Duration,
    gestureToken: GestureToken,
  ) = command(gestureToken) { fitCalls += fit to duration }

  override suspend fun moveByAwaitingTransition(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken,
  ) = moveBy(deltaX, deltaY, duration, gestureToken)

  override suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken,
  ) = scaleBy(scale, anchor, duration, gestureToken)

  override suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    gestureToken: GestureToken,
    anchor: DpOffset?,
  ) = rotateAndPitchBy(bearingDelta, pitchDelta, duration, anchor, gestureToken)

  override var capabilities = setOf(TapFamily.Tap, TapFamily.LongPress)
  val deliveredTapFamilies = mutableListOf<TapFamily>()
  var clicks = 0
  var longClicks = 0

  override fun capture(family: TapFamily): MapClickPath =
    MapClickPath({ !state.isClosed }) {
      deliveredTapFamilies += family
      when (family) {
        TapFamily.Tap -> clicks++
        TapFamily.LongPress -> longClicks++
        else -> Unit
      }
      ClickResult.Pass
    }

  data class ScaleCall(val scale: Double, val anchor: DpOffset?)

  data class RotateCall(val bearingDelta: Double, val pitchDelta: Double, val anchor: DpOffset?)
}
