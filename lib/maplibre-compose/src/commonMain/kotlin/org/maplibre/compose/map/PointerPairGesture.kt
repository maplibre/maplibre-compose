package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/** The selected contacts share camera ownership, but each recognized component has its own ID. */
internal class PointerPairGesture(
  private val target: GestureTarget,
  private val options: MapGestures,
  private val currentOptions: () -> MapGestures,
  private val ids: GestureIds,
  private val density: Density,
  event: PointerEvent,
  first: PointerInputChange,
  second: PointerInputChange,
  private val begin: () -> GestureToken?,
  private val onRecognized: () -> Unit,
  private val retainAuthority: () -> Boolean,
) {
  private data class Sample(
    val first: Offset,
    val second: Offset,
    val time: Long,
    val pressure: Float,
  ) {
    val centroid = (first + second) / 2f
    val distance = hypot((second.x - first.x).toDouble(), (second.y - first.y).toDouble())
    val angle = atan2((second.y - first.y).toDouble(), (second.x - first.x).toDouble())
    val horizontalAngle = abs(angle.toDegrees()).let { min(it, 180.0 - it) }

    constructor(
      first: PointerInputChange,
      second: PointerInputChange,
    ) : this(
      first.position,
      second.position,
      maxOf(first.uptimeMillis, second.uptimeMillis),
      first.pressure + second.pressure,
    )
  }

  private class Component(val binding: GestureBinding) {
    var sample: GesturePointerSample? = null
    val active: Boolean
      get() = sample != null
  }

  val firstId: PointerId = first.id
  val secondId: PointerId = second.id
  private var start = Sample(first, second)
  private var previous = start
  private var metadata =
    event.gestureSample(0, target, density, start.centroid, setOf(first.type, second.type))
  private val pan = component("dragPan")
  private val pinch = component("pinchZoom")
  private val rotate = component("twoFingerRotate")
  private val shove = component("twoFingerTilt")
  private val components = listOfNotNull(pan, pinch, rotate, shove)
  val hasDemand: Boolean
    get() = components.isNotEmpty()

  private val fingerVelocity = GestureVelocityTracker()
  private val centroidVelocity = GestureVelocityTracker()
  private val transformVelocity = GestureVelocityTracker()
  private var totalRotation = 0.0
  private var rotationSpan = start.distance
  private var lastSpanDelta = 0.0
  private var lastScaleWasOut = false
  private var lastRotation = 0.0
  private var closed = false
  private var token: GestureToken? = null

  init {
    record(first, start)
  }

  private fun component(id: String): Component? =
    options.binding(id).takeIf { it.matches(metadata, contact = true) }?.let(::Component)

  fun matches(first: PointerInputChange, second: PointerInputChange): Boolean =
    first.id == firstId && second.id == secondId

  /** Contact-count changes and backwards timestamps establish a fresh motion baseline. */
  fun rebase(event: PointerEvent, first: PointerInputChange, second: PointerInputChange) {
    start = Sample(first, second)
    previous = start
    metadata =
      event.gestureSample(0, target, density, start.centroid, setOf(first.type, second.type))
    rotationSpan = start.distance
    totalRotation = 0.0
    lastSpanDelta = 0.0
    lastRotation = 0.0
    fingerVelocity.resetTracking()
    centroidVelocity.resetTracking()
    transformVelocity.resetTracking()
    record(first, start)
  }

  fun move(event: PointerEvent, first: PointerInputChange, second: PointerInputChange) {
    if (closed) return
    val current = Sample(first, second)
    val elapsed = current.time - previous.time
    if (elapsed < 0) {
      rebase(event, first, second)
      return
    }
    if (!GestureMath.hasStablePressure(current.pressure, previous.pressure)) {
      previous = current
      return
    }
    if (
      current.distance < GestureMath.MINIMUM_TWO_FINGER_SPAN_DP * density.density ||
        previous.distance <= 0
    ) {
      rebase(event, first, second)
      return
    }
    metadata =
      event.gestureSample(0, target, density, current.centroid, setOf(first.type, second.type))
    components.forEach { component ->
      component.sample?.let { component.sample = metadata.copy(gestureId = it.gestureId) }
    }
    val centroidDelta = current.centroid - previous.centroid
    val centroidFromStart = current.centroid - start.centroid
    val span = (current.distance - start.distance) * 2 / density.density
    val spanDelta = (current.distance - previous.distance) * 2 / density.density
    val angle = normalizedAngle(current.angle - previous.angle).toDegrees()
    val angleFromStart = normalizedAngle(current.angle - start.angle).toDegrees()
    totalRotation += angle
    record(first, current)

    val startRotate =
      angleFromStart != 0.0 &&
        rotate != null &&
        !rotate.active &&
        shove?.active != true &&
        GestureMath.shouldStartRotation(
          angleFromStart,
          angle,
          elapsed,
          rotate.binding.settings.startAngle,
        )
    val scaleSlop = pinch?.binding?.settings?.startSpanSlop?.value?.toDouble() ?: 0.0
    val scaleSpan =
      if (rotate?.active == true) (current.distance - rotationSpan) * 2 / density.density else span
    val startPinch =
      scaleSpan != 0.0 &&
        pinch != null &&
        !pinch.active &&
        shove?.active != true &&
        GestureMath.shouldStartScale(
          scaleSpan,
          spanDelta,
          elapsed,
          angle,
          if (rotate?.active == true) maxOf(scaleSlop, GestureMath.SCALE_START_WHILE_ROTATING_DP)
          else scaleSlop,
        )
    val startShove =
      centroidFromStart.y != 0f &&
        shove != null &&
        !shove.active &&
        GestureMath.shouldStartShove(
          (centroidFromStart.y / density.density).toDouble(),
          current.horizontalAngle,
          shove.binding.settings.startSlop.value.toDouble(),
        )

    var panDelta = centroidDelta
    var scale = current.distance / previous.distance
    var rotation = angle
    var shoveDelta = centroidDelta.y
    // Keep the existing rotation/scale/shove priority, while allowing recognized components to
    // yield to rotation or shove later in the stream.
    if (startRotate) {
      pinch?.let { cancel(it, GestureCancellationReason.BindingChanged) }
      rotationSpan = current.distance
      rotation =
        angleFromStart - sign(angleFromStart) * checkNotNull(rotate).binding.settings.startAngle
      if (!start(rotate)) return
    } else if (startPinch) {
      val threshold =
        if (rotate?.active == true) maxOf(scaleSlop, GestureMath.SCALE_START_WHILE_ROTATING_DP)
        else scaleSlop
      val baseline = if (rotate?.active == true) rotationSpan else start.distance
      scale = current.distance / (baseline + sign(scaleSpan) * threshold * density.density / 2)
      if (!start(checkNotNull(pinch))) return
    } else if (startShove) {
      listOfNotNull(pan, pinch, rotate).forEach {
        cancel(it, GestureCancellationReason.BindingChanged)
      }
      shoveDelta =
        centroidFromStart.y -
          sign(centroidFromStart.y) *
            checkNotNull(shove).binding.settings.startSlop.value *
            density.density
      if (!start(shove)) return
    }

    if (shove?.active != true && pan != null && !pan.active) {
      val slop = pan.binding.settings.startSlop.value * density.density
      val distance = centroidFromStart.getDistance()
      if (distance > 0 && distance >= slop) {
        panDelta = centroidFromStart * ((distance - slop) / distance)
        if (!start(pan)) return
      }
    }

    if (shove?.active == true && shoveDelta != 0f) {
      observe(
        shove,
        ShoveEvent.Delta(checkNotNull(shove.sample), (shoveDelta / density.density).dp),
      )
      if (!retainAuthority()) return
      target.rotateAndPitchBy(
        0.0,
        shoveDelta / density.density * shove.binding.settings.pitchDegreesPerDp,
        gestureToken = token,
      )
    } else if (shove?.active != true) {
      if (pan?.active == true && panDelta != Offset.Zero) {
        val delta = DpOffset((panDelta.x / density.density).dp, (panDelta.y / density.density).dp)
        observe(pan, DragEvent.Delta(checkNotNull(pan.sample), delta))
        if (!retainAuthority()) return
        target.moveBy(delta.x.value.toDouble(), delta.y.value.toDouble(), gestureToken = token)
      }
      if (pinch?.active == true && scale.isFinite() && scale > 0 && abs(scale - 1) >= 1e-6) {
        observe(pinch, PinchEvent.Delta(checkNotNull(pinch.sample), scale))
        if (!retainAuthority()) return
        target.scaleBy(
          GestureMath.pinchScale(scale).pow(pinch.binding.settings.zoomScale),
          pinch.binding.anchor(metadata),
          gestureToken = token,
        )
        lastSpanDelta = abs(current.distance - previous.distance) * 2
        lastScaleWasOut = scale < 1
      }
      if (rotate?.active == true && abs(rotation) >= 1e-6) {
        observe(rotate, RotateEvent.Delta(checkNotNull(rotate.sample), rotation))
        if (!retainAuthority()) return
        target.rotateAndPitchBy(
          -rotation * rotate.binding.settings.rotationScale,
          0.0,
          anchor = rotate.binding.anchor(metadata),
          gestureToken = token,
        )
        lastRotation = -rotation
      }
    }
    previous = current
    if (components.any { it.active })
      event.changes.filter { it.id == firstId || it.id == secondId }.forEach { it.consume() }
  }

  private fun start(component: Component): Boolean {
    token = begin()
    if (token?.acceptsCommands != true) {
      retainAuthority()
      return false
    }
    onRecognized()
    val sample = metadata.copy(gestureId = ids.next())
    component.sample = sample
    val origin =
      DpOffset((start.centroid.x / density.density).dp, (start.centroid.y / density.density).dp)
    observe(
      component,
      when (component) {
        pan -> DragEvent.Start(sample, origin)
        pinch -> PinchEvent.Start(sample, origin)
        rotate -> RotateEvent.Start(sample, origin)
        else -> ShoveEvent.Start(sample, origin)
      },
    )
    return retainAuthority()
  }

  private fun observe(component: Component, event: PointerGestureEvent) {
    val handlers = currentOptions().binding(component.binding.id).handlers
    when (event) {
      is DragEvent -> handlers.observe(event)
      is PinchEvent -> handlers.observe(event)
      is RotateEvent -> handlers.observe(event)
      is ShoveEvent -> handlers.observe(event)
      else -> error("Unexpected pair event")
    }
  }

  private fun cancel(component: Component, reason: GestureCancellationReason) {
    val sample = component.sample ?: return
    component.sample = null
    observe(
      component,
      when (component) {
        pan -> DragEvent.Cancel(sample, reason)
        pinch -> PinchEvent.Cancel(sample, reason)
        rotate -> RotateEvent.Cancel(sample, reason)
        else -> ShoveEvent.Cancel(sample, reason)
      },
    )
  }

  fun cancel(reason: GestureCancellationReason) {
    closed = true
    // Remove each component before invoking user code, including when cleanup is reentrant.
    var failure: Throwable? = null
    components.forEach {
      try {
        cancel(it, reason)
      } catch (cause: Throwable) {
        if (failure == null) failure = cause else checkNotNull(failure).addSuppressed(cause)
      }
    }
    failure?.let { throw it }
  }

  fun end(uptimeMillis: Long = metadata.uptimeMillis): PairContinuation? {
    if (closed) return null
    val continuation = continuation()
    closed = true
    val linear = centroidVelocity.calculateVelocity()
    val velocity =
      ScreenVelocity(
        (linear.x / density.density).toDouble(),
        (linear.y / density.density).toDouble(),
      )
    val transform = transformVelocity.calculateVelocity()
    components.forEach { component ->
      val last = component.sample ?: return@forEach
      val sample =
        last.copy(
          uptimeMillis = uptimeMillis,
          position = target.positionFromScreenLocation(last.screenOffset),
        )
      component.sample = null
      observe(
        component,
        when (component) {
          pan -> DragEvent.End(sample, velocity)
          pinch -> PinchEvent.End(sample, transform.x.toDouble())
          rotate -> RotateEvent.End(sample, transform.y.toDouble())
          else -> ShoveEvent.End(sample, velocity)
        },
      )
      if (token?.acceptsCommands == false) {
        retainAuthority()
        return null
      }
    }
    return continuation
  }

  private fun record(first: PointerInputChange, sample: Sample) {
    fingerVelocity.addPointerInputChange(first)
    centroidVelocity.addPosition(sample.time, sample.centroid)
    val zoom =
      if (sample.distance > 0 && start.distance > 0)
        ln(GestureMath.pinchScale(sample.distance / start.distance)) / ln(2.0)
      else 0.0
    transformVelocity.addPosition(sample.time, Offset(zoom.toFloat(), totalRotation.toFloat()))
  }

  private fun continuation(): PairContinuation? {
    val finger = fingerVelocity.calculateVelocity()
    val centroid = centroidVelocity.calculateVelocity()
    val panFling =
      pan
        ?.takeIf { it.active }
        ?.binding
        ?.settings
        ?.fling
        ?.let {
          GestureMath.fling(
            (centroid.x / density.density).toDouble(),
            (centroid.y / density.density).toDouble(),
            it,
          )
        }
    val scale =
      pinch
        ?.takeIf { it.active }
        ?.binding
        ?.settings
        ?.velocityContinuation
        ?.let {
          GestureMath.scaleVelocity(
              finger.x.toDouble(),
              finger.y.toDouble(),
              lastSpanDelta,
              density.density.toDouble(),
              lastScaleWasOut,
              it,
            )
            ?.let { response ->
              response.copy(zoomDelta = response.zoomDelta * pinch.binding.settings.zoomScale)
            }
        }
    val rotation =
      rotate
        ?.takeIf { it.active }
        ?.binding
        ?.settings
        ?.velocityContinuation
        ?.let {
          GestureMath.rotationVelocity(
              finger.x.toDouble(),
              finger.y.toDouble(),
              previous.centroid.x.toDouble(),
              previous.centroid.y.toDouble(),
              lastRotation,
              density.density.toDouble(),
              pinch?.active == true,
              it,
            )
            ?.let { response ->
              response.copy(
                initialDegreesPerFrame =
                  response.initialDegreesPerFrame * rotate.binding.settings.rotationScale
              )
            }
        }
    val tilt =
      shove
        ?.takeIf { it.active }
        ?.binding
        ?.settings
        ?.tiltContinuation
        ?.let {
          GestureMath.tiltVelocity(
            centroid.y / density.density * shove.binding.settings.pitchDegreesPerDp,
            it,
          )
        }
    if (panFling == null && scale == null && rotation == null && tilt == null) return null
    return PairContinuation(
      panFling,
      scale,
      rotation,
      tilt,
      pinch?.binding?.anchor(metadata),
      rotate?.binding?.anchor(metadata),
    )
  }
}

internal data class PairContinuation(
  val pan: GestureMath.Fling?,
  val scale: GestureMath.ScaleVelocity?,
  val rotation: GestureMath.RotationVelocity?,
  val tilt: GestureMath.TiltVelocity?,
  val scaleAnchor: DpOffset?,
  val rotationAnchor: DpOffset?,
)

private fun Double.toDegrees(): Double = this * 180.0 / kotlin.math.PI

private fun normalizedAngle(radians: Double): Double =
  atan2(kotlin.math.sin(radians), kotlin.math.cos(radians))
