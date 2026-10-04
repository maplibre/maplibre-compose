package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.camera.internal.boxZoomFit
import org.maplibre.compose.camera.internal.inputFitBoundsAwaitingTransition
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputRotateAndPitchBy
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.interaction.DragResponse
import org.maplibre.compose.interaction.QuickZoomDirection

/** What the one-contact drags of a [PointerGesture] share. */
internal class DragContext(
  val target: CameraInputTarget,
  val options: InputConfiguration,
  val density: Density,
  val boxZoom: BoxZoomPreview,
  val viewportSize: () -> IntSize,
  val touchSlopPx: Float,
  val maximumFlingVelocity: Float,
) {
  /** An unspecified slop defers to the host's touch slop. */
  fun slopPx(slop: Dp): Float = if (slop.isSpecified) slop.value * density.density else touchSlopPx

  /** The camera drag the bindings select for [sample], or null when they select none. */
  fun cameraDrag(
    first: PointerInputChange,
    sample: GesturePointerSample,
    afterContactChange: Boolean = false,
  ): SingleDrag? =
    when (options.bindings.drag.select(sample, options.camera.settings)) {
      DragResponse.Pan -> SingleDrag.Pan(this, first, afterContactChange)
      DragResponse.RotatePitch -> SingleDrag.RotatePitch(this, first, afterContactChange)
      DragResponse.FitBounds -> SingleDrag.FitBounds(this, first, afterContactChange)
      DragResponse.None,
      null -> null
    }
}

/**
 * One contact moving the camera. It starts once the contact crosses its slop, then applies each
 * movement and, on release, supplies momentum.
 */
internal sealed class SingleDrag(
  protected val context: DragContext,
  first: PointerInputChange,
  touchSlop: Dp,
  mouseSlop: Dp,
  private val afterContactChange: Boolean,
  verticalOnly: Boolean = false,
) {
  private val recognizer: PointerDrag
  private val velocity = PointerDragVelocity(context.maximumFlingVelocity)

  init {
    val slop = context.slopPx(if (first.type == PointerType.Mouse) mouseSlop else touchSlop)
    // Lifting one contact often shifts the other. Require the host's normal touch slop
    // before treating that remaining contact as a new drag.
    recognizer =
      PointerDrag(
        first,
        if (afterContactChange) maxOf(context.touchSlopPx, slop) else slop,
        verticalOnly,
      )
    velocity.begin(first, afterContactChange)
  }

  /** Mouse button and modifier changes compare this to the newly selected response. */
  abstract val response: DragResponse?

  /** Components this drag takes over from momentum and start callbacks of an earlier gesture. */
  abstract val components: Set<CameraComponent>

  val active: Boolean
    get() = recognizer.active

  /** A quick zoom whose motion crossed the threshold sideways first. */
  val rejected: Boolean
    get() = recognizer.rejected

  /** Returns the movement to apply, excluding the start slop, or null before the drag starts. */
  fun move(change: PointerInputChange): PointerDrag.Motion? {
    val motion = recognizer.move(change) ?: return null
    if (motion.started) {
      // A departing contact can cross slop at high speed. Estimate this drag's momentum from
      // movement after recognition, not from that transition.
      if (afterContactChange) velocity.recognize(change)
      onStart(motion, change)
    }
    velocity.addPointerInputChange(change)
    return motion
  }

  protected open fun onStart(motion: PointerDrag.Motion, change: PointerInputChange) {}

  abstract fun update(
    delta: Offset,
    change: PointerInputChange,
    sample: GesturePointerSample,
    token: CameraInputToken?,
  )

  abstract fun momentum(sample: GesturePointerSample): PointerContinuation?

  open fun release(sample: GesturePointerSample, session: GestureInputSession?) {}

  open fun cancel() {}

  protected fun releaseVelocity(): Velocity = velocity.calculateVelocity()

  protected fun dp(pixels: Float): Double = pixels.toDouble() / context.density.density

  protected val cameraSettings: CameraSettings
    get() = context.options.camera.settings

  class Pan(context: DragContext, first: PointerInputChange, afterContactChange: Boolean) :
    SingleDrag(
      context,
      first,
      context.options.bindings.drag.pan.startSlop,
      context.options.bindings.drag.pan.mouseStartSlop,
      afterContactChange,
    ) {
    override val response = DragResponse.Pan
    override val components = setOf(CameraComponent.Pan)

    override fun update(
      delta: Offset,
      change: PointerInputChange,
      sample: GesturePointerSample,
      token: CameraInputToken?,
    ) = context.target.inputPanBy(dp(delta.x), dp(delta.y), gestureToken = token)

    override fun momentum(sample: GesturePointerSample): PointerContinuation? {
      val tuning = cameraSettings.pan.momentum.takeIf { it.enabled } ?: return null
      val velocity = releaseVelocity()
      val fling = GestureMath.fling(dp(velocity.x), dp(velocity.y), tuning) ?: return null
      return PointerContinuation(pan = fling)
    }
  }

  class RotatePitch(context: DragContext, first: PointerInputChange, afterContactChange: Boolean) :
    SingleDrag(
      context,
      first,
      context.options.bindings.drag.rotatePitch.startSlop,
      context.options.bindings.drag.rotatePitch.mouseStartSlop,
      afterContactChange,
    ) {
    private val settings = context.options.bindings.drag.rotatePitch
    override val response = DragResponse.RotatePitch
    override val components = setOf(CameraComponent.Rotate, CameraComponent.Pitch)

    override fun update(
      delta: Offset,
      change: PointerInputChange,
      sample: GesturePointerSample,
      token: CameraInputToken?,
    ) =
      context.target.inputRotateAndPitchBy(
        dp(delta.x) * settings.bearingDegreesPerDp,
        dp(delta.y) * settings.pitchDegreesPerDp,
        anchor = settings.anchor.location(sample),
        gestureToken = token,
      )

    override fun momentum(sample: GesturePointerSample): PointerContinuation? {
      if (!cameraSettings.pitch.enabled) return null
      val tuning = cameraSettings.pitch.momentum.takeIf { it.enabled } ?: return null
      val velocity = releaseVelocity()
      val pitch =
        GestureMath.pitchVelocity(dp(velocity.y) * settings.pitchDegreesPerDp, tuning)
          ?: return null
      return PointerContinuation(pitch = pitch)
    }
  }

  /** Draws a box and, on release, fits the camera to it. */
  class FitBounds(context: DragContext, first: PointerInputChange, afterContactChange: Boolean) :
    SingleDrag(
      context,
      first,
      context.options.bindings.drag.fitBounds.startSlop,
      context.options.bindings.drag.fitBounds.mouseStartSlop,
      afterContactChange,
    ) {
    private val origin = first.position
    override val response = DragResponse.FitBounds
    override val components = CameraComponent.entries.toSet()

    override fun onStart(motion: PointerDrag.Motion, change: PointerInputChange) {
      context.boxZoom.start(
        origin.toLogicalDpOffset(context.density),
        change.position.toLogicalDpOffset(context.density),
      )
    }

    override fun update(
      delta: Offset,
      change: PointerInputChange,
      sample: GesturePointerSample,
      token: CameraInputToken?,
    ) = context.boxZoom.move(sample.screenOffset)

    override fun momentum(sample: GesturePointerSample): PointerContinuation? = null

    override fun release(sample: GesturePointerSample, session: GestureInputSession?) {
      context.boxZoom.move(sample.screenOffset)
      val selection = context.boxZoom.clear() ?: return
      val fit = context.target.boxZoomFit(selection) ?: return
      if (session == null || !session.token.acceptsCommands) return
      session.scope.launch {
        context.target.inputFitBoundsAwaitingTransition(
          fit,
          context.options.scaledAnimationDuration(),
          session.token,
        )
      }
    }

    override fun cancel() {
      context.boxZoom.clear()
    }
  }

  /** The second press of a tap that drags vertically to zoom. */
  class QuickZoom(context: DragContext, first: PointerInputChange) :
    SingleDrag(
      context,
      first,
      context.options.bindings.tapDrag.startSlop / 2,
      context.options.bindings.tapDrag.startSlop / 2,
      afterContactChange = false,
      verticalOnly = true,
    ) {
    private val binding = context.options.bindings.tapDrag
    private val direction = if (binding.direction == QuickZoomDirection.DownZoomsIn) 1.0 else -1.0
    private var originY = first.position.y
    private var appliedDelta = 0.0

    /** No mouse button or modifier change selects a quick zoom. */
    override val response: DragResponse? = null
    override val components = setOf(CameraComponent.Zoom)

    // The recognizer removes slop from the first delta; zoom is measured from that same origin.
    override fun onStart(motion: PointerDrag.Motion, change: PointerInputChange) {
      originY += motion.thresholdOffset.y
    }

    override fun update(
      delta: Offset,
      change: PointerInputChange,
      sample: GesturePointerSample,
      token: CameraInputToken?,
    ) {
      val targetDelta = zoomDelta((change.position.y - originY).toDouble())
      context.target.inputScaleBy(
        zoomLevelsToScale(targetDelta - appliedDelta),
        binding.anchor.location(sample),
        gestureToken = token,
      )
      appliedDelta = targetDelta
    }

    override fun momentum(sample: GesturePointerSample): PointerContinuation? {
      val tuning = cameraSettings.zoom.momentum.takeIf { it.enabled } ?: return null
      val velocity = releaseVelocity()
      val scale = GestureMath.scaleVelocity(zoomDelta(velocity.y.toDouble()), tuning) ?: return null
      return PointerContinuation(scale = scale, scaleAnchor = binding.anchor.location(sample))
    }

    private fun zoomDelta(pixels: Double): Double =
      GestureMath.quickZoomDelta(
        pixels,
        context.viewportSize().height.toDouble(),
        binding.zoomLevelsPerViewport * direction,
      )
  }
}
