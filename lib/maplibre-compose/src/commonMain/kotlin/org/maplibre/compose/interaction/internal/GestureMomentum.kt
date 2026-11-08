package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.time.DurationUnit
import org.maplibre.compose.camera.CubicBezier
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.interaction.MomentumGlide
import org.maplibre.compose.style.scaledBy

/**
 * How fast each part moved when a gesture released, in its unit per second: dp for pan, zoom levels
 * for zoom, and degrees for bearing and pitch. Contact changes merge releases by part.
 */
internal data class ReleaseVelocity(
  val pan: Offset? = null,
  val zoom: Double? = null,
  val bearing: Double? = null,
  val pitch: Double? = null,
  val zoomAnchor: DpOffset? = null,
  val bearingAnchor: DpOffset? = null,
) {
  fun without(component: CameraComponent): ReleaseVelocity =
    when (component) {
      CameraComponent.Pan -> copy(pan = null)
      CameraComponent.Zoom -> copy(zoom = null, zoomAnchor = null)
      CameraComponent.Rotate -> copy(bearing = null, bearingAnchor = null)
      CameraComponent.Pitch -> copy(pitch = null)
    }

  fun without(components: Set<CameraComponent>): ReleaseVelocity =
    components.fold(this) { velocity, component -> velocity.without(component) }

  fun withPrevious(previous: ReleaseVelocity?): ReleaseVelocity =
    copy(
      pan = pan ?: previous?.pan,
      zoom = zoom ?: previous?.zoom,
      bearing = bearing ?: previous?.bearing,
      pitch = pitch ?: previous?.pitch,
      zoomAnchor = if (zoom != null) zoomAnchor else previous?.zoomAnchor,
      bearingAnchor = if (bearing != null) bearingAnchor else previous?.bearingAnchor,
    )
}

/** How fast a cancelled glide still moved, in each part's unit per second. */
internal data class GlideVelocity(
  val panX: Double = 0.0,
  val panY: Double = 0.0,
  val zoom: Double = 0.0,
  val bearing: Double = 0.0,
  val pitch: Double = 0.0,
)

/** A decay with its plan, as `mln_momentum_decay` takes it. */
internal sealed interface GlideDecay {
  val minSpeed: Double
  val maxDistance: Double

  data class Friction(val settings: MomentumDecay.Friction) : GlideDecay {
    override val minSpeed: Double
      get() = settings.minSpeed

    override val maxDistance: Double
      get() = settings.maxDistance
  }

  data class Ease(
    val glide: MomentumGlide,
    override val minSpeed: Double,
    override val maxDistance: Double,
  ) : GlideDecay
}

internal data class PanMomentumPart(
  val velocityX: Double,
  val velocityY: Double,
  val decay: GlideDecay,
)

/** A part with neither a velocity nor a snap is absent from [GestureMomentum]. */
internal data class AnchoredMomentumPart(
  val velocity: Double?,
  val decay: GlideDecay,
  val anchor: DpOffset?,
  val snap: Snapping?,
)

/** What a gesture's end continues, as `mln_gesture_momentum` describes it. */
internal data class GestureMomentum(
  val pan: PanMomentumPart? = null,
  val zoom: AnchoredMomentumPart? = null,
  val bearing: AnchoredMomentumPart? = null,
  val pitch: AnchoredMomentumPart? = null,
)

/**
 * The momentum [settings] give a release. A part moves only when its momentum is on, its release
 * clears its minimum speed, and [start] admits it; [start] runs the part's start callback. A part
 * settles only when [changed] holds it. [boost], a glide that this gesture cancelled, adds to each
 * part that releases the same way. Returns null when nothing moves or settles.
 */
internal fun resolveGestureMomentum(
  settings: CameraSettings,
  release: ReleaseVelocity?,
  boost: GlideVelocity?,
  changed: Set<CameraComponent>,
  start: (CameraComponent) -> Boolean,
  snapDurationScale: Float,
): GestureMomentum? {
  val anchors = settings.pan.enabled
  fun snap(component: CameraComponent, snapping: Snapping): Snapping? =
    snapping
      .takeIf { it.enabled && component in changed }
      ?.let { it.copy(duration = it.duration.scaledBy(snapDurationScale)) }

  val pan =
    release?.pan?.let { velocity ->
      var x = velocity.x.toDouble()
      var y = velocity.y.toDouble()
      val decay = settings.pan.momentum.takeIf { it.enabled }?.decay ?: return@let null
      val speed = hypot(x, y)
      if (!(speed > 0.0) || !speed.isFinite() || speed < decay.minSpeed) return@let null
      if (boost != null && x * boost.panX + y * boost.panY > 0.0) {
        x += boost.panX
        y += boost.panY
      }
      val planned = decay.planned(hypot(x, y)) ?: return@let null
      if (!start(CameraComponent.Pan)) return@let null
      PanMomentumPart(x, y, planned)
    }

  fun anchored(
    component: CameraComponent,
    momentum: Momentum,
    released: Double?,
    boosted: Double,
    anchor: DpOffset?,
    snapping: Snapping,
  ): AnchoredMomentumPart? {
    val decay = momentum.decay
    val velocity =
      released
        ?.takeIf { momentum.enabled && it.isFinite() && abs(it) >= decay.minSpeed && it != 0.0 }
        ?.let { if (sign(it) == sign(boosted)) it + boosted else it }
    val planned = velocity?.let { decay.planned(abs(it)) }
    val moving = planned != null && start(component)
    val snap = snap(component, snapping)
    if (!moving && snap == null) return null
    return AnchoredMomentumPart(
      velocity.takeIf { moving },
      planned.takeIf { moving } ?: decay.unplanned(),
      anchor.takeIf { anchors },
      snap,
    )
  }

  val zoom =
    anchored(
      CameraComponent.Zoom,
      settings.zoom.momentum,
      release?.zoom,
      boost?.zoom ?: 0.0,
      release?.zoomAnchor,
      settings.zoom.snapping,
    )
  val bearing =
    anchored(
      CameraComponent.Rotate,
      settings.rotate.momentum,
      release?.bearing,
      boost?.bearing ?: 0.0,
      release?.bearingAnchor,
      settings.rotate.snapping,
    )
  val pitch =
    anchored(
      CameraComponent.Pitch,
      settings.pitch.momentum,
      release?.pitch,
      boost?.pitch ?: 0.0,
      null,
      settings.pitch.snapping,
    )
  if (pan == null && zoom == null && bearing == null && pitch == null) return null
  return GestureMomentum(pan, zoom, bearing, pitch)
}

/** Plans an eased glide from [speed]; friction plans itself. Null when the planner declines. */
private fun MomentumDecay.planned(speed: Double): GlideDecay? =
  when (this) {
    is MomentumDecay.Friction -> GlideDecay.Friction(this)
    is MomentumDecay.Ease -> planner.plan(speed)?.let { GlideDecay.Ease(it, minSpeed, maxDistance) }
  }

/** The decay of a part that only settles; its glide parameters go unused. */
private fun MomentumDecay.unplanned(): GlideDecay =
  when (this) {
    is MomentumDecay.Friction -> GlideDecay.Friction(this)
    is MomentumDecay.Ease -> GlideDecay.Friction(MomentumDefaults.PanFriction)
  }

/**
 * MapLibre's `UnitBezier`: a cubic Bézier from (0, 0) to (1, 1), solved the same way so both
 * engines ease alike.
 */
internal class UnitBezier(x1: Double, y1: Double, x2: Double, y2: Double) {
  constructor(curve: CubicBezier) : this(curve.x1, curve.y1, curve.x2, curve.y2)

  private val cx = 3.0 * x1
  private val bx = 3.0 * (x2 - x1) - cx
  private val ax = 1.0 - cx - bx
  private val cy = 3.0 * y1
  private val by = 3.0 * (y2 - y1) - cy
  private val ay = 1.0 - cy - by

  fun sampleCurveX(t: Double): Double = ((ax * t + bx) * t + cx) * t

  fun sampleCurveY(t: Double): Double = ((ay * t + by) * t + cy) * t

  fun sampleCurveDerivativeX(t: Double): Double = (3.0 * ax * t + 2.0 * bx) * t + cx

  fun sampleCurveDerivativeY(t: Double): Double = (3.0 * ay * t + 2.0 * by) * t + cy

  /** The curve parameter whose x is [x]: Newton's method, then bisection. */
  fun solveCurveX(x: Double, epsilon: Double): Double {
    var t2 = x
    repeat(8) {
      val x2 = sampleCurveX(t2) - x
      if (abs(x2) < epsilon) return t2
      val d2 = sampleCurveDerivativeX(t2)
      if (abs(d2) < 1e-6) return@repeat
      t2 -= x2 / d2
    }
    var t0 = 0.0
    var t1 = 1.0
    t2 = x
    if (t2 < t0) return t0
    if (t2 > t1) return t1
    while (t0 < t1) {
      val x2 = sampleCurveX(t2)
      if (abs(x2 - x) < epsilon) return t2
      if (x > x2) t0 = t2 else t1 = t2
      t2 = (t1 - t0) * 0.5 + t0
    }
    return t2
  }

  fun solve(x: Double, epsilon: Double): Double = sampleCurveY(solveCurveX(x, epsilon))

  companion object {
    /** The curve that MapLibre Native's snaps and camera animations ease along by default. */
    val DefaultTransition = UnitBezier(0.0, 0.0, 0.25, 1.0)
  }
}

/**
 * How far one part glides, over how long, and along which curve: MapLibre Native's momentum glide,
 * so the browser glides as native does. Times are in seconds.
 */
internal class Glide
private constructor(
  val distance: Double,
  val duration: Double,
  /** For friction, the decay rate per second. */
  private val rate: Double,
  private val easing: UnitBezier?,
) {
  /** The share of the distance covered after [elapsed], from 0 to 1. */
  fun progress(elapsed: Double): Double {
    if (elapsed >= duration) return 1.0
    if (elapsed <= 0.0) return 0.0
    if (easing == null) return expm1(-rate * elapsed) / expm1(-rate * duration)
    return easing.solve(elapsed / duration, 1e-6)
  }

  /** The share of the distance covered per second after [elapsed]. */
  fun speed(elapsed: Double): Double {
    if (elapsed >= duration) return 0.0
    val t = max(0.0, elapsed)
    if (easing == null) return rate * exp(-rate * t) / -expm1(-rate * duration)
    // The slope dy/dx of the curve, from its derivatives in its own parameter, which starts just
    // past 0 where both can be 0.
    val p = max(easing.solveCurveX(t / duration, 1e-6), 1e-6)
    val dx = easing.sampleCurveDerivativeX(p)
    return if (dx > 0.0) easing.sampleCurveDerivativeY(p) / dx / duration else 0.0
  }

  companion object {
    /** Plans a release at [speed], or returns null when it doesn't move. */
    fun plan(speed: Double, decay: GlideDecay): Glide? {
      if (!(speed > 0.0) || speed < decay.minSpeed) return null
      val distance: Double
      val seconds: Double
      var rate = 0.0
      var easing: UnitBezier? = null
      when (decay) {
        is GlideDecay.Friction -> {
          val friction = decay.settings
          val halfLife = friction.halfLife.toDouble(DurationUnit.SECONDS)
          if (!(halfLife > 0.0) || !(friction.stopSpeed > 0.0)) return null
          rate = ln(2.0) / halfLife
          val capped =
            min(min(speed, friction.maxSpeed), friction.stopSpeed + rate * friction.maxDistance)
          if (capped <= friction.stopSpeed) return null
          seconds = ln(capped / friction.stopSpeed) / rate
          distance = (capped - friction.stopSpeed) / rate
        }
        is GlideDecay.Ease -> {
          distance = min(decay.glide.distance, decay.maxDistance)
          seconds = decay.glide.duration.toDouble(DurationUnit.SECONDS)
          easing = UnitBezier(decay.glide.easing)
        }
      }
      if (!(distance > 0.0) || !(seconds > 0.0) || !seconds.isFinite()) return null
      return Glide(distance, seconds, rate, easing)
    }
  }
}

private const val PanStepDp = 16.0
private const val MaxPanSteps = 100

/**
 * Runs [momentum] frame by frame through camera jumps, as MapLibre Native runs it for an engine
 * that can't take it: each part glides on its own, then settles on its snap. Pans move in steps no
 * longer than a drag event's, so a tilted map moves its content the same distance on the screen in
 * every direction. Cancellation reports the speed the glide still had through [token].
 *
 * A step that bounds or limits hold in place stops its part, which then settles at once, as in
 * MapLibre Native.
 */
internal suspend fun CameraInputTarget.runMomentum(
  momentum: GestureMomentum,
  token: CameraInputToken,
) {
  val pan =
    momentum.pan?.let { part ->
      val speed = hypot(part.velocityX, part.velocityY)
      Glide.plan(speed, part.decay)?.let {
        PanRun(it, part.velocityX * it.distance / speed, part.velocityY * it.distance / speed)
      }
    }
  val parts =
    listOfNotNull(
      momentum.zoom?.let { ScalarRun(ScalarKind.Zoom, it) },
      momentum.bearing?.let { ScalarRun(ScalarKind.Bearing, it) },
      momentum.pitch?.let { ScalarRun(ScalarKind.Pitch, it) },
    )
  parts.forEach { part ->
    part.snapPending =
      part.snap != null && (part.moving || part.snap.delta(part.value(this), part.circular) != 0.0)
  }
  if (pan == null && parts.none { it.active }) return

  var elapsed = 0.0
  try {
    val startNanos = withFrameNanos { it }
    while (pan?.moving == true || parts.any { it.active }) {
      val now = withFrameNanos { it }
      elapsed = (now - startNanos) / 1e9
      pan?.advance(elapsed)?.let { fraction ->
        val before = getCameraPosition().center
        GestureMath.forEachScreenSpaceStep(
          pan.totalX * fraction,
          pan.totalY * fraction,
          PanStepDp,
          MaxPanSteps,
        ) { x, y ->
          moveBy(x, y, gestureToken = token)
        }
        if (getCameraPosition().center == before) pan.moving = false
      }
      for (part in parts) {
        part.advance(elapsed)?.let {
          val before = part.value(this)
          part.step(this, token, it * part.total)
          // Bearing has no limits.
          if (!part.circular && part.value(this) == before) part.moving = false
        }
        if (part.snapPending && !part.moving) {
          part.snapPending = false
          val delta = checkNotNull(part.snap).delta(part.value(this), part.circular)
          if (delta != 0.0) {
            part.snapDelta = delta
            part.snapStart = elapsed
          }
        }
        part.settle(this, token, elapsed)
      }
    }
  } catch (cancelled: CancellationException) {
    fun ScalarRun?.speed() = this?.glide?.takeIf { moving }?.let { total * it.speed(elapsed) }
    val panShare = pan?.takeIf { it.moving }?.glide?.speed(elapsed) ?: 0.0
    token.glideCancelled(
      GlideVelocity(
        panX = (pan?.totalX ?: 0.0) * panShare,
        panY = (pan?.totalY ?: 0.0) * panShare,
        zoom = parts.find { it.kind == ScalarKind.Zoom }.speed() ?: 0.0,
        bearing = parts.find { it.kind == ScalarKind.Bearing }.speed() ?: 0.0,
        pitch = parts.find { it.kind == ScalarKind.Pitch }.speed() ?: 0.0,
      ),
    )
    throw cancelled
  }
}

private class PanRun(val glide: Glide, val totalX: Double, val totalY: Double) {
  var moving = true
  private var applied = 0.0

  /** The share of the distance to apply this frame, or null when nothing moves. */
  fun advance(elapsed: Double): Double? {
    if (!moving) return null
    val progress = glide.progress(elapsed)
    val fraction = progress - applied
    applied = progress
    if (progress >= 1.0) moving = false
    return fraction.takeIf { it != 0.0 }
  }
}

private enum class ScalarKind {
  Zoom,
  Bearing,
  Pitch,
}

private class ScalarRun(val kind: ScalarKind, part: AnchoredMomentumPart) {
  val anchor = part.anchor
  val snap = part.snap
  val circular = kind == ScalarKind.Bearing
  val glide = part.velocity?.let { Glide.plan(abs(it), part.decay) }
  val total = glide?.let { it.distance * sign(checkNotNull(part.velocity)) } ?: 0.0
  var moving = glide != null
  private var applied = 0.0
  var snapPending = false
  var snapDelta: Double? = null
  var snapStart = 0.0
  private var snapApplied = 0.0

  val active: Boolean
    get() = moving || snapPending || snapDelta != null

  fun value(target: CameraInputTarget): Double =
    target.getCameraPosition().let {
      when (kind) {
        ScalarKind.Zoom -> it.zoom
        ScalarKind.Bearing -> it.bearing
        ScalarKind.Pitch -> it.pitch
      }
    }

  fun advance(elapsed: Double): Double? {
    val glide = glide?.takeIf { moving } ?: return null
    val progress = glide.progress(elapsed)
    val fraction = progress - applied
    applied = progress
    if (progress >= 1.0) moving = false
    return fraction.takeIf { it != 0.0 }
  }

  fun step(target: CameraInputTarget, token: CameraInputToken, amount: Double) {
    when (kind) {
      ScalarKind.Zoom -> target.scaleBy(zoomLevelsToScale(amount), anchor, gestureToken = token)
      ScalarKind.Bearing ->
        target.rotateAndPitchBy(amount, 0.0, anchor, gestureToken = token, feedback = true)
      ScalarKind.Pitch -> target.rotateAndPitchBy(0.0, amount, anchor, gestureToken = token)
    }
  }

  fun settle(target: CameraInputTarget, token: CameraInputToken, elapsed: Double) {
    val delta = snapDelta ?: return
    val duration = checkNotNull(snap).duration.toDouble(DurationUnit.SECONDS)
    val t = if (duration == 0.0) 1.0 else ((elapsed - snapStart) / duration).coerceIn(0.0, 1.0)
    val eased = if (t >= 1.0) 1.0 else UnitBezier.DefaultTransition.solve(t, 0.001)
    val fraction = eased - snapApplied
    snapApplied = eased
    if (fraction != 0.0) step(target, token, delta * fraction)
    if (t >= 1.0) snapDelta = null
  }
}
