package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.interaction.internal.FeatureClickDispatcher
import org.maplibre.compose.interaction.internal.RecognizedMapInput
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.rememberStyleComposition

/**
 * Holds one presentation reservation of [state] while this object is remembered.
 *
 * It reserves in the apply phase rather than during composition. Compose forgets removed groups
 * before it remembers new ones, so when a recomposition replaces the group that holds a map, the
 * old map releases [state] before the new one reserves it. Two maps that stay in composition
 * together still conflict.
 */
private class MapStateAttachment(
  val state: MapState,
  private val owner: MapPresentationOwnerToken,
) : RememberObserver {
  private var token: MapPresentationToken? = null

  /** Whether the apply phase has reserved [state] for this presentation. */
  var isReserved by mutableStateOf(false)
    private set

  override fun onRemembered() {
    token = state.reservePresentation(owner)
    isReserved = true
  }

  override fun onForgotten() {
    release()
  }

  override fun onAbandoned() = Unit

  fun publish(map: MapAdapter) {
    state.publishPresentation(checkNotNull(token) { "The map presentation is not reserved" }, map)
  }

  fun release(map: MapAdapter? = null) {
    token?.let { state.releasePresentation(it, map) }
  }
}

/**
 * Style composition, callbacks, and recognized input for one presentation of [state]. Returns null
 * while another map presents [state].
 */
@Composable
internal fun <T> MapPresentationContent(
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
  content: @Composable (MapPresentationBinding) -> T,
): T? {
  val attachment =
    remember(state, presentationOwner) { MapStateAttachment(state, presentationOwner) }
  // The other map may be leaving in this same recomposition, and then this map takes over its
  // engine. Until the apply phase settles which, touching that engine would disturb a live map.
  if (!attachment.isReserved && state.lifecycle.isPresentedByOtherOwner(presentationOwner)) {
    return null
  }
  // The dispatcher reads this state directly: a click can arrive between the style binding's
  // invalidation and the recomposition that clears it.
  val rememberedStyleState = remember { mutableStateOf<StyleBinding?>(null) }
  var rememberedStyle by rememberedStyleState
  val desiredRevisionState =
    rememberStyleComposition(
      content = state.styleContent,
      maybeStyle = rememberedStyle,
      replaceableSourceIds =
        state.styleAuthority.desiredStyleRevision.sources.mapTo(mutableSetOf()) { it.id },
      replaceableLayerIds =
        state.styleAuthority.desiredStyleRevision.layers.mapTo(mutableSetOf()) {
          it.definition.id
        },
      applyRevision = { style, revision ->
        // A loaded engine can receive content before its physical presentation is published.
        val map = state.lifecycle.currentAdapter()
        if (map != null && state.styleAuthority.style.currentLoadedStyle() === style) {
          state.styleAuthority.applyStyleRevision(map, style, revision)
        }
      },
    )
  val mapAttachment = state.currentMapAttachment
  val currentInteractions = rememberUpdatedState(options.interactions)
  SideEffect { state.gestureAuthority.updateConfiguration(options.interactions.camera) }
  // The style subcomposition publishes into a revision state it re-creates per loaded style, and
  // the dispatcher must keep its identity because the pointer input holding it does not restart.
  val currentDesiredRevision = rememberUpdatedState(desiredRevisionState)
  val clickDispatcher =
    remember(state) {
      FeatureClickDispatcher(
        state = state,
        desiredRevision = currentDesiredRevision,
        loadedStyle = rememberedStyleState,
        interactions = currentInteractions,
      )
    }
  val inputScope = rememberCoroutineScope()
  DisposableEffect(mapAttachment, clickDispatcher) {
    val target =
      mapAttachment?.adapter as? CameraInputTarget ?: return@DisposableEffect onDispose {}
    val input =
      RecognizedMapInput(
        target,
        clickDispatcher::capture,
        clickDispatcher::hasHandlers,
        { currentInteractions.value },
        inputScope,
      )
    state.recognizedInput = input
    onDispose {
      input.cancel()
      if (state.recognizedInput === input) state.recognizedInput = null
    }
  }
  val adapterCallbacks =
    remember(attachment, mapAttachment) {
      MapStateCallbacks(state) { map, style ->
        rememberedStyle = style
        state.attachmentAuthority.synchronizeCamera(map)
      }
    }

  return content(
    MapPresentationBinding(
      update = update@{ map ->
          if (state.isClosed) return@update
          map.setViewportInsets(options.viewportInsets)
          map.setCameraConstraints(options.cameraConstraints)
          map.setRenderSettings(options.renderOptions)
          map.setTileLodSettings(options.renderOptions.tileLod)
          attachment.publish(map)
        },
      onReset = {
        attachment.release()
        rememberedStyle = null
      },
      callbacks = adapterCallbacks,
      clicks = clickDispatcher,
    )
  )
}

internal class MapPresentationBinding(
  val update: (MapAdapter) -> Unit,
  val onReset: () -> Unit,
  val callbacks: MapAdapter.Callbacks,
  val clicks: FeatureClickDispatcher,
)
