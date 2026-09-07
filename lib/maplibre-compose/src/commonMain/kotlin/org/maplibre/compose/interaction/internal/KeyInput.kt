package org.maplibre.compose.interaction.internal

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import org.maplibre.compose.camera.internal.inputPanByAwaitingTransition
import org.maplibre.compose.camera.internal.inputRotateAndPitchByAwaitingTransition
import org.maplibre.compose.camera.internal.inputScaleByAwaitingTransition
import org.maplibre.compose.interaction.CameraInputOrigin
import org.maplibre.compose.interaction.KeyGestureEvent
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.MapInteractions

internal data class KeyClaim(
  val response: KeyResponse?,
  val gestureId: Long,
)

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
  val claimedKeys = mutableStateMapOf<Key, KeyClaim>()

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
 * Held keys share camera ownership; repeating a step replaces its easing, not its input lifetime.
 */
internal class KeyInput(
  private val target: CameraInputTarget,
  private val options: () -> MapInteractions,
  private val focus: InputFocus,
  private val ids: GestureIds,
  private val scope: CoroutineScope,
) {
  private var session: GestureInputSession? = null
  private var step: Job? = null
  private var structuralKey: Any? = null

  fun configure(key: Any) {
    if (structuralKey == key) return
    structuralKey = key
    cancel()
  }

  fun onEvent(event: KeyEvent): Boolean {
    val modifiers = buildSet {
      if (event.isShiftPressed) add(KeyModifier.Shift)
      if (event.isCtrlPressed) add(KeyModifier.Ctrl)
      if (event.isAltPressed) add(KeyModifier.Alt)
      if (event.isMetaPressed) add(KeyModifier.Meta)
    }
    return onSample(event.key, event.type, modifiers, keyDispatchUptimeMillis())
  }

  fun onSample(
    key: Key,
    type: KeyEventType,
    modifiers: Set<KeyModifier>,
    uptimeMillis: Long,
  ): Boolean {
    if (session?.token?.acceptsCommands == false) cancel()

    // Releases close an existing claim even if its binding was removed while the key was held.
    if (type == KeyEventType.KeyUp) {
      val released = focus.claimedKeys.remove(key) ?: return false
      val component = released.response?.component
      if (
        component != null && focus.claimedKeys.values.none { it.response?.component == component }
      )
        session?.token?.rearm(component)
      finishIfReleased()
      return true
    }
    if (type != KeyEventType.KeyDown) return false

    val previous = focus.claimedKeys[key]
    if (previous != null && previous.response == null) return true
    val settings = options()
    if (!settings.bindings.keys.hasCameraBindings(settings.camera)) return false
    val action =
      (previous?.response ?: settings.bindings.keys.select(key, modifiers, settings.camera))
        ?.takeUnless { it == KeyResponse.None } ?: return false

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
    val claim = previous ?: KeyClaim(action, ids.next()).also { focus.claimedKeys[key] = it }

    target.observeInput()
    val event = KeyGestureEvent(claim.gestureId, uptimeMillis, key, modifiers, previous != null)
    if (!action.isCamera) {
      try {
        settings.bindings.keys.onEvent?.invoke(event)
        if (!focus.isEngaged) cancel()
      } catch (error: Throwable) {
        cancel()
        throw error
      }
      return true
    }

    val current =
      session
        ?: run {
          lateinit var created: GestureInputSession
          created =
            GestureInputSession(scope, target, origin = CameraInputOrigin.Key) {
              if (session === created) cancel()
            }
          created.also { session = it }
        }

    try {
      settings.bindings.keys.onEvent?.invoke(event)
      // An observer can take over the camera; its key response must then stop here.
      if (!current.token.acceptsCommands) {
        cancel()
        return true
      }

      step?.cancel()
      step =
        current.scope.launch(start = CoroutineStart.UNDISPATCHED) {
          try {
            applyStep(
              action,
              settings.bindings.keys,
              settings.scaledAnimationDuration(),
              current.token,
            )
          } catch (error: CancellationException) {
            throw error
          } catch (error: Throwable) {
            cancel()
            throw error
          }
        }
    } catch (error: Throwable) {
      cancel()
      throw error
    }

    return true
  }

  private fun hasHeldCameraKeys(): Boolean =
    focus.claimedKeys.values.any { it.response?.isCamera == true }

  private fun finishIfReleased() {
    if (hasHeldCameraKeys()) return
    step = null
    // Retain the response so focus loss or a binding change can still cancel its easing.
    session?.end()
  }

  fun cancel() {
    val previous = session
    session = null
    step = null
    focus.claimedKeys.keys.toList().forEach { key ->
      focus.claimedKeys[key] = focus.claimedKeys.getValue(key).copy(response = null)
    }
    previous?.cancel()
  }

  private suspend fun applyStep(
    action: KeyResponse,
    keys: KeyBinding,
    duration: Duration,
    token: CameraInputToken,
  ) {
    val pan = keys.panStep.value.toDouble()
    with(target) {
      when (action) {
        KeyResponse.PanLeft -> inputPanByAwaitingTransition(pan, 0.0, duration, token)
        KeyResponse.PanRight -> inputPanByAwaitingTransition(-pan, 0.0, duration, token)
        KeyResponse.PanUp -> inputPanByAwaitingTransition(0.0, pan, duration, token)
        KeyResponse.PanDown -> inputPanByAwaitingTransition(0.0, -pan, duration, token)
        KeyResponse.ZoomIn ->
          inputScaleByAwaitingTransition(zoomLevelsToScale(keys.zoomStep), null, duration, token)
        KeyResponse.ZoomOut ->
          inputScaleByAwaitingTransition(zoomLevelsToScale(-keys.zoomStep), null, duration, token)
        KeyResponse.RotateLeft ->
          inputRotateAndPitchByAwaitingTransition(-keys.rotateStep, 0.0, duration, token)
        KeyResponse.RotateRight ->
          inputRotateAndPitchByAwaitingTransition(keys.rotateStep, 0.0, duration, token)
        KeyResponse.TiltUp ->
          inputRotateAndPitchByAwaitingTransition(0.0, keys.pitchStep, duration, token)
        KeyResponse.TiltDown ->
          inputRotateAndPitchByAwaitingTransition(0.0, -keys.pitchStep, duration, token)
        else -> Unit
      }
    }
  }
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
