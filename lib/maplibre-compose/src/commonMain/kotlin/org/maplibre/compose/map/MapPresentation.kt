package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.interaction.internal.FeatureClickDispatcher
import org.maplibre.compose.interaction.internal.RecognizedMapInput
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.rememberStyleComposition

private class MapStateAttachment(
  val state: MapState,
  private val token: MapPresentationToken,
) {
  fun publish(map: MapAdapter) {
    state.publishPresentation(token, map)
  }

  fun release(map: MapAdapter? = null) {
    state.releasePresentation(token, map)
  }

  fun markStyleReady(map: MapAdapter): Boolean = state.markStyleReady(map)

  fun markStyleFailed(map: MapAdapter, reason: String?) {
    state.markStyleFailed(map, reason)
  }

  suspend fun reconcileStyleRevision(map: MapAdapter, revision: DesiredStyleRevision) {
    state.beginStyleRevision(map, revision)
    try {
      state.updateStyleResources(map, map.reconcileStyleRevision(revision))
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      state.markStyleFailed(map, error.message)
    }
  }
}

/** Style composition, callbacks, and recognized input for one presentation of [state]. */
@Composable
internal fun MapPresentationContent(
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
  content: @Composable (MapPresentationBinding) -> Unit,
) {
  val token = remember(state, presentationOwner) { state.reservePresentation(presentationOwner) }
  val attachment = remember(state, token) { MapStateAttachment(state, token) }
  DisposableEffect(attachment) { onDispose { attachment.release() } }
  // The dispatcher reads this state directly: a click can arrive between the style binding's
  // invalidation and the recomposition that clears it.
  val rememberedStyleState = remember { mutableStateOf<StyleBinding?>(null) }
  var rememberedStyle by rememberedStyleState
  val desiredRevisionState =
    rememberStyleComposition(
      content = state.styleContent,
      maybeStyle = rememberedStyle,
      replaceableSourceIds = state.desiredStyleRevision.sources.mapTo(mutableSetOf()) { it.id },
      replaceableLayerIds =
        state.desiredStyleRevision.layers.mapTo(mutableSetOf()) {
          it.definition.id
        },
    )
  val desiredRevision by desiredRevisionState
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
  var retainedRevisionReplayed by remember(rememberedStyle, mapAttachment) { mutableStateOf(false) }

  LaunchedEffect(rememberedStyle, mapAttachment, attachment) {
    val map = mapAttachment?.adapter ?: return@LaunchedEffect
    if (rememberedStyle == null) return@LaunchedEffect
    try {
      state.updateStyleResources(map, map.replayStyleRevision(state.desiredStyleRevision))
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      state.runtime.logger?.w(error) { "Could not replay the retained style revision" }
    } finally {
      retainedRevisionReplayed = true
    }
  }

  LaunchedEffect(rememberedStyle, desiredRevision, mapAttachment, retainedRevisionReplayed) {
    if (!retainedRevisionReplayed) return@LaunchedEffect
    val map = mapAttachment?.adapter ?: return@LaunchedEffect
    val revision = desiredRevision ?: return@LaunchedEffect
    attachment.reconcileStyleRevision(map, revision)
  }

  val adapterCallbacks =
    remember(attachment, mapAttachment) {
      object : MapAdapter.Callbacks {
        private fun synchronizeCamera(map: MapAdapter): MapAttachment? {
          return state.synchronizeCamera(map)
        }

        override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
          if (!state.updateLoadedStyle(map, style)) return
          rememberedStyle = style
          synchronizeCamera(map)
        }

        override fun onStyleReady(map: MapAdapter) {
          attachment.markStyleReady(map)
        }

        override fun onStyleFailed(map: MapAdapter, reason: String?) {
          attachment.markStyleFailed(map, reason)
        }

        override fun onStyleSourcesChanged(map: MapAdapter, sourceId: String?) {
          state.refreshStyleSources(map, sourceId)
        }

        override fun onEvent(map: MapAdapter, event: MapEvent) {
          state.onEvent(map, event)
        }

        override fun resolveMissingImage(map: MapAdapter, imageId: String) =
          state.resolveMissingImage(map, imageId)

        override fun onGestureActive(map: MapAdapter, active: Boolean) {
          state.setGestureActive(map, active)
        }

        override fun onViewportChanged(map: MapAdapter) {
          synchronizeCamera(map)
        }
      }
    }

  content(
    MapPresentationBinding(
      update = update@{ map ->
          if (state.isClosed) return@update
          map.setCameraPadding(options.cameraPadding)
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
