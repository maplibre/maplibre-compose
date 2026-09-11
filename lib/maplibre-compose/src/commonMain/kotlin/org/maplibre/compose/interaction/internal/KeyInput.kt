package org.maplibre.compose.interaction.internal

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputPanByAwaitingTransition
import org.maplibre.compose.camera.internal.inputRotateAndPitchBy
import org.maplibre.compose.camera.internal.inputRotateAndPitchByAwaitingTransition
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.camera.internal.inputScaleByAwaitingTransition
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.KeyResponse

/**
 * The focus and engagement of one [mapInput] node. The node writes both states, and [onChanged]
 * reports each engagement write.
 *
 * A focused node holds Compose focus. An engaged node consumes the keys that pan, zoom, rotate, and
 * tilt. A node that is focused and not engaged passes those keys through, so focus traversal
 * continues from the map.
 */
internal class InputFocus(private val onChanged: (engaged: Boolean) -> Unit) {
  /**
   * Focus interactions for the indication the node draws. The map reports focus only while it is a
   * traversal candidate: an engaged map is a mode, and the camera moving under the keys is its
   * indication.
   */
  val indicationInteractions = MutableInteractionSource()

  /** A null response retains only consumption until release after cancellation. */
  val claimedKeys = mutableStateMapOf<Key, KeyResponse?>()

  /** Engagement belongs to the key handler, so a map without one never engages or stays engaged. */
  var hasKeyBindings = false
    set(value) {
      field = value
      if (!value) disengage()
    }

  private var isFocused = false
  private var engagedByKey = false
  private var shownFocus: FocusInteraction.Focus? = null

  var isEngaged: Boolean by mutableStateOf(false)
    private set

  /** Whether Back releases the map. A pointer press engages without claiming Back. */
  val consumesBack: Boolean
    get() = isEngaged && engagedByKey

  fun onFocusChanged(focused: Boolean) {
    isFocused = focused
    if (!focused) {
      disengage()
      claimedKeys.clear()
    }
    showFocus()
  }

  /** Returns false when the node is not focused, because only a focused node engages. */
  fun engage(byKey: Boolean): Boolean {
    if (!isFocused || !hasKeyBindings) return false
    isEngaged = true
    engagedByKey = byKey
    showFocus()
    onChanged(true)
    return true
  }

  /** Returns false when the node was not engaged. */
  fun disengage(): Boolean {
    if (!isEngaged) return false
    isEngaged = false
    showFocus()
    onChanged(false)
    return true
  }

  private fun showFocus() {
    val show = isFocused && !isEngaged
    val shown = shownFocus
    if (show && shown == null) {
      shownFocus = FocusInteraction.Focus().also { indicationInteractions.tryEmit(it) }
    } else if (!show && shown != null) {
      shownFocus = null
      indicationInteractions.tryEmit(FocusInteraction.Unfocus(shown))
    }
  }

  /** Reports the current state again, for a listener that missed earlier writes. */
  fun replay() = onChanged(isEngaged)
}

/**
 * A camera key moves the map by one step per [InputConfiguration.animationDuration] for as long as
 * it is held, applied every frame through the gesture session. A release before a full step eases
 * the remainder over the rest of that duration, so a tap still moves exactly one step. Held keys
 * add their directions, so opposite keys cancel and a pan can combine with a zoom. OS key repeats
 * are consumed and ignored.
 *
 * The animator duration scale applies to the eased remainder only. At scale zero a tap jumps one
 * step and a held key still moves every frame. A zero [InputConfiguration.animationDuration] has no
 * rate to hold at, so every press and repeat jumps one step.
 */
internal class KeyInput(
  private val target: CameraInputTarget,
  private val options: () -> InputConfiguration,
  private val focus: InputFocus,
  private val scope: CoroutineScope,
) {
  private var session: GestureInputSession? = null
  private var hold: Job? = null
  /** The fraction of a step each held camera key has moved through the hold loop. */
  private val progress = mutableMapOf<Key, Double>()
  private var settings: InputConfiguration.Settings? = null

  fun configure(value: InputConfiguration.Settings) {
    if (settings == value) return
    settings = value
    cancel()
  }

  fun onEvent(event: KeyEvent): Boolean {
    val modifiers = buildSet {
      if (event.isShiftPressed) add(KeyModifier.Shift)
      if (event.isCtrlPressed) add(KeyModifier.Ctrl)
      if (event.isAltPressed) add(KeyModifier.Alt)
      if (event.isMetaPressed) add(KeyModifier.Meta)
    }
    return onSample(event.key, event.type, modifiers)
  }

  fun onSample(
    key: Key,
    type: KeyEventType,
    modifiers: Set<KeyModifier>,
  ): Boolean {
    if (session?.token?.acceptsCommands == false) cancel()

    // Releases close an existing claim even if its binding was removed while the key was held.
    if (type == KeyEventType.KeyUp) {
      if (key !in focus.claimedKeys) return false
      val action = focus.claimedKeys.remove(key)
      if (action?.isCamera == true && hold != null) completeStep(key, action)
      val component = action?.component
      if (component != null && focus.claimedKeys.values.none { it?.component == component })
        session?.token?.rearm(component)
      finishIfReleased()
      return true
    }
    if (type != KeyEventType.KeyDown) return false

    val previous = focus.claimedKeys[key]
    if (key in focus.claimedKeys && previous == null) return true
    val settings = options()
    if (!settings.hasCameraKeys) return false
    val action =
      (previous ?: settings.bindings.keys.select(key, modifiers, settings.camera.settings))
        ?.takeUnless {
          it == KeyResponse.None
        } ?: return false

    val consumed =
      when (action) {
        KeyResponse.Engage -> focus.engage(byKey = true) || previous != null
        KeyResponse.Disengage -> focus.disengage() || previous != null
        KeyResponse.Back -> (focus.consumesBack && focus.disengage()) || previous != null
        else -> focus.isEngaged
      }
    if (!consumed) return false

    // A press after every camera key was released starts a new lifetime, even if the last
    // release's easing is still draining. Overlapping held keys keep their shared authority.
    if (action.isCamera && previous == null && !hasHeldCameraKeys() && session != null) cancel()
    if (previous == null) focus.claimedKeys[key] = action

    target.observeInput()
    if (!action.isCamera) {
      if (!focus.isEngaged) cancel()
      return true
    }

    val current =
      session
        ?: run {
          lateinit var created: GestureInputSession
          created =
            GestureInputSession(
              scope,
              target,
              animationDuration = settings.scaledAnimationDuration(),
            ) {
              if (session === created) cancel()
            }
          created.also { session = it }
        }

    if (settings.animationDuration == Duration.ZERO) {
      launchStep(current, action, settings, fraction = 1.0)
    } else if (previous == null && hold == null) {
      hold = launchHold(current, settings)
    }
    return true
  }

  private fun hasHeldCameraKeys(): Boolean = focus.claimedKeys.values.any { it?.isCamera == true }

  private fun launchHold(session: GestureInputSession, settings: InputConfiguration): Job =
    session.scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        val keys = settings.bindings.keys
        val durationNanos = settings.animationDuration.inWholeNanoseconds.toDouble()
        var previous = withFrameNanos { it }
        var fraction = 0.0
        while (true) {
          var motion = KeyMotion.None
          for ((key, action) in focus.claimedKeys) {
            if (action?.isCamera != true) continue
            val moved = progress[key]
            // A key pressed since the previous frame starts moving at the next one.
            if (moved == null) {
              progress[key] = 0.0
            } else {
              progress[key] = moved + fraction
              motion += action.motion
            }
          }
          motion.apply(keys, fraction, session.token)
          val now = withFrameNanos { it }
          fraction = (now - previous) / durationNanos
          previous = now
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        cancel()
        throw error
      }
    }

  /** Eases the rest of a step that the hold loop had not finished. */
  private fun completeStep(key: Key, action: KeyResponse) {
    val current = session ?: return
    val remaining = 1.0 - (progress.remove(key) ?: 0.0)
    if (remaining > 0.0) launchStep(current, action, options(), fraction = remaining)
  }

  private fun launchStep(
    session: GestureInputSession,
    action: KeyResponse,
    settings: InputConfiguration,
    fraction: Double,
  ) {
    session.scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        action.motion.applyEased(
          settings.bindings.keys,
          fraction,
          settings.scaledAnimationDuration() * fraction,
          session.token,
        )
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        cancel()
        throw error
      }
    }
  }

  private fun finishIfReleased() {
    if (hasHeldCameraKeys()) return
    hold?.cancel()
    hold = null
    progress.clear()
    // Retain the session so focus loss or a binding change can still cancel its easing.
    session?.end()
  }

  fun cancel() {
    val previous = session
    session = null
    hold = null
    progress.clear()
    focus.claimedKeys.keys.toList().forEach { key ->
      focus.claimedKeys[key] = null
    }
    previous?.cancel()
  }

  private suspend fun KeyMotion.applyEased(
    keys: KeyBinding,
    fraction: Double,
    duration: Duration,
    token: CameraInputToken,
  ) {
    val pan = keys.panStep.value * fraction
    with(target) {
      if (x != 0.0 || y != 0.0) inputPanByAwaitingTransition(x * pan, y * pan, duration, token)
      if (zoom != 0.0)
        inputScaleByAwaitingTransition(
          zoomLevelsToScale(zoom * keys.zoomStep * fraction),
          null,
          duration,
          token,
        )
      if (bearing != 0.0 || pitch != 0.0)
        inputRotateAndPitchByAwaitingTransition(
          bearing * keys.rotateStep * fraction,
          pitch * keys.pitchStep * fraction,
          duration,
          token,
        )
    }
  }

  private fun KeyMotion.apply(keys: KeyBinding, fraction: Double, token: CameraInputToken) {
    val pan = keys.panStep.value * fraction
    with(target) {
      inputPanBy(x * pan, y * pan, token)
      if (zoom != 0.0) inputScaleBy(zoomLevelsToScale(zoom * keys.zoomStep * fraction), null, token)
      inputRotateAndPitchBy(
        bearing * keys.rotateStep * fraction,
        pitch * keys.pitchStep * fraction,
        gestureToken = token,
        feedback = false,
      )
    }
  }
}

/** Directions in units of the configured steps. */
private data class KeyMotion(
  val x: Double = 0.0,
  val y: Double = 0.0,
  val zoom: Double = 0.0,
  val bearing: Double = 0.0,
  val pitch: Double = 0.0,
) {
  operator fun plus(other: KeyMotion) =
    KeyMotion(
      x + other.x,
      y + other.y,
      zoom + other.zoom,
      bearing + other.bearing,
      pitch + other.pitch,
    )

  companion object {
    val None = KeyMotion()
  }
}

private val KeyResponse.motion: KeyMotion
  get() =
    when (this) {
      KeyResponse.PanLeft -> KeyMotion(x = 1.0)
      KeyResponse.PanRight -> KeyMotion(x = -1.0)
      KeyResponse.PanUp -> KeyMotion(y = 1.0)
      KeyResponse.PanDown -> KeyMotion(y = -1.0)
      KeyResponse.ZoomIn -> KeyMotion(zoom = 1.0)
      KeyResponse.ZoomOut -> KeyMotion(zoom = -1.0)
      KeyResponse.RotateLeft -> KeyMotion(bearing = -1.0)
      KeyResponse.RotateRight -> KeyMotion(bearing = 1.0)
      KeyResponse.TiltUp -> KeyMotion(pitch = 1.0)
      KeyResponse.TiltDown -> KeyMotion(pitch = -1.0)
      else -> KeyMotion.None
    }

private val KeyResponse.component: CameraComponent?
  get() =
    when (this) {
      KeyResponse.PanLeft,
      KeyResponse.PanRight,
      KeyResponse.PanUp,
      KeyResponse.PanDown -> CameraComponent.Pan
      KeyResponse.ZoomIn,
      KeyResponse.ZoomOut -> CameraComponent.Zoom
      KeyResponse.RotateLeft,
      KeyResponse.RotateRight -> CameraComponent.Rotate
      KeyResponse.TiltUp,
      KeyResponse.TiltDown -> CameraComponent.Tilt
      else -> null
    }
