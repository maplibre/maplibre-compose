package org.maplibre.compose.input

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min

/**
 * Recognition uses screen pixels. Projection, response gains, and mutation ownership belong to the
 * caller.
 */
internal enum class TransformComponent {
  Pan,
  Scale,
  Rotation,
  VerticalDrag,
}

internal data class PairSample(
  val first: Offset,
  val second: Offset,
  val time: Long,
  val pressure: Float,
) {
  constructor(
    first: PointerInputChange,
    second: PointerInputChange,
  ) : this(
    first.position,
    second.position,
    maxOf(first.uptimeMillis, second.uptimeMillis),
    first.pressure + second.pressure,
  )

  val centroid = (first + second) / 2f
  val distance = hypot((second.x - first.x).toDouble(), (second.y - first.y).toDouble())
  val angle = atan2((second.y - first.y).toDouble(), (second.x - first.x).toDouble())
  val horizontalAngle = abs(angle * 180.0 / kotlin.math.PI).let { min(it, 180.0 - it) }
}

internal class PairMotion(
  val origin: PairSample,
  val previous: PairSample,
  val current: PairSample,
) {
  val elapsed = current.time - previous.time
  val pan = current.centroid - previous.centroid
  val displacement = current.centroid - origin.centroid
  val rotation = degrees(current.angle - previous.angle)
  val rotationFromStart = degrees(current.angle - origin.angle)
  val scale = current.distance / previous.distance

  private fun degrees(value: Double) =
    atan2(kotlin.math.sin(value), kotlin.math.cos(value)) * 180.0 / kotlin.math.PI
}

/** A policy selects components and subtracts its thresholds from the first delivered deltas. */
internal data class TransformDecision(
  val start: Set<TransformComponent> = emptySet(),
  val cancel: Set<TransformComponent> = emptySet(),
  val pan: Offset,
  val scale: Double,
  val rotation: Double,
  val verticalDrag: Float,
)

internal interface PointerTransformPolicy {
  fun reset(sample: PairSample) = Unit

  fun accepts(previous: PairSample, current: PairSample): Boolean = true

  fun needsRebase(previous: PairSample, current: PairSample): Boolean = previous.distance <= 0

  fun recognize(motion: PairMotion, active: Set<TransformComponent>): TransformDecision
}

internal data class TransformVelocity(
  val pointer: Velocity,
  val centroid: Velocity,
  val logarithmicScale: Double,
  val rotation: Double,
  val lastSpanDelta: Double,
  val scalingOut: Boolean,
  val lastRotation: Double,
)

/** One selected pair; callbacks return false when the consumer has lost the right to act. */
internal class PointerTransform(
  first: PointerInputChange,
  second: PointerInputChange,
  private val policy: PointerTransformPolicy,
  private val onStart: (TransformComponent, Offset) -> Boolean,
  private val onDelta: (TransformComponent, TransformDecision) -> Boolean,
  private val onEnd: (TransformComponent, TransformVelocity) -> Boolean,
  private val onCancel: (TransformComponent) -> Unit,
) {
  val firstId = first.id
  val secondId = second.id
  var origin = PairSample(first, second)
    private set

  var current = origin
    private set

  private val components = linkedSetOf<TransformComponent>()
  val active: Set<TransformComponent>
    get() = components.toSet()

  private val fingerVelocity = GestureVelocityTracker()
  private val centroidVelocity = GestureVelocityTracker()
  private val transformVelocity = GestureVelocityTracker()
  private var totalRotation = 0.0
  private var lastSpanDelta = 0.0
  private var lastScaleWasOut = false
  private var lastRotation = 0.0
  private var closed = false

  init {
    policy.reset(origin)
    record(first, origin)
  }

  fun rebase(first: PointerInputChange, second: PointerInputChange) {
    origin = PairSample(first, second)
    current = origin
    totalRotation = 0.0
    lastSpanDelta = 0.0
    lastRotation = 0.0
    fingerVelocity.resetTracking()
    centroidVelocity.resetTracking()
    transformVelocity.resetTracking()
    policy.reset(origin)
    record(first, origin)
  }

  fun move(first: PointerInputChange, second: PointerInputChange): Boolean {
    if (closed) return false
    val next = PairSample(first, second)
    if (next.time < current.time) {
      rebase(first, second)
      return false
    }
    if (!policy.accepts(current, next)) {
      current = next
      return false
    }
    if (policy.needsRebase(current, next)) {
      rebase(first, second)
      return false
    }
    val motion = PairMotion(origin, current, next)
    totalRotation += motion.rotation
    record(first, next)
    current = next
    val decision = policy.recognize(motion, active)
    decision.cancel.forEach { cancel(it) }
    for (component in decision.start) {
      if (closed) return false
      if (components.add(component) && !onStart(component, origin.centroid)) return false
    }
    for (component in components.sortedBy { it.ordinal }) {
      if (closed || component !in components) return false
      val moved =
        when (component) {
          TransformComponent.Pan -> decision.pan != Offset.Zero
          TransformComponent.Scale ->
            decision.scale.isFinite() && decision.scale > 0 && abs(decision.scale - 1) >= 1e-6
          TransformComponent.Rotation -> abs(decision.rotation) >= 1e-6
          TransformComponent.VerticalDrag -> decision.verticalDrag != 0f
        }
      if (moved) {
        if (!onDelta(component, decision)) return false
        when (component) {
          TransformComponent.Scale -> {
            lastSpanDelta = abs(next.distance - motion.previous.distance) * 2
            lastScaleWasOut = decision.scale < 1
          }
          TransformComponent.Rotation -> lastRotation = decision.rotation
          else -> Unit
        }
      }
    }
    return components.isNotEmpty()
  }

  fun velocity(): TransformVelocity {
    val transform = transformVelocity.calculateVelocity()
    return TransformVelocity(
      fingerVelocity.calculateVelocity(),
      centroidVelocity.calculateVelocity(),
      transform.x.toDouble(),
      transform.y.toDouble(),
      lastSpanDelta,
      lastScaleWasOut,
      lastRotation,
    )
  }

  fun end(): Boolean {
    if (closed) return false
    closed = true
    val velocity = velocity()
    for (component in components.sortedBy { it.ordinal }) {
      if (components.remove(component) && !onEnd(component, velocity)) return false
    }
    return true
  }

  fun cancel() {
    closed = true
    var failure: Throwable? = null
    components
      .sortedBy { it.ordinal }
      .forEach {
        try {
          cancel(it)
        } catch (cause: Throwable) {
          if (failure == null) failure = cause else checkNotNull(failure).addSuppressed(cause)
        }
      }
    failure?.let { throw it }
  }

  private fun cancel(component: TransformComponent) {
    if (components.remove(component)) onCancel(component)
  }

  private fun record(first: PointerInputChange, sample: PairSample) {
    fingerVelocity.addPointerInputChange(first)
    centroidVelocity.addPosition(sample.time, sample.centroid)
    val scale =
      if (sample.distance > 0 && origin.distance > 0) ln(sample.distance / origin.distance) else 0.0
    transformVelocity.addPosition(sample.time, Offset(scale.toFloat(), totalRotation.toFloat()))
  }
}
