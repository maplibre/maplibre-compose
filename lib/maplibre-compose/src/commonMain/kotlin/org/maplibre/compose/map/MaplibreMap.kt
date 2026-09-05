package org.maplibre.compose.map

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MapOverlayHost
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.overlay.include
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.rememberStyleComposition
import org.maplibre.compose.util.MapClickHandler

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
      map.reconcileStyleRevision(revision)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      state.markStyleFailed(map, error.message)
    }
  }
}

/**
 * Displays [state] on a map surface.
 *
 * The caller controls the lifetime of [state]. This composable attaches the map surface while the
 * call remains in composition. [overlay] draws Compose UI over the map. The default draws
 * [MapOverlay.Default]. A supplied block replaces the default.
 *
 * The map is a focus target, and the overlay is a focus group. Focus modifiers on [modifier] apply
 * to the map, and a control in the overlay keeps its own focus properties.
 *
 * Tap handlers run after binding callbacks and before interactive layers. A consuming handler stops
 * delivery and camera fallthrough. [onUnhandledClick] runs after unconsumed ordinary taps. Null
 * handlers do not contribute recognition demand.
 */
@Composable
public fun MaplibreMap(
  modifier: Modifier = Modifier,
  state: MapState = rememberMapState(),
  cameraPadding: PaddingValues = PaddingValues(0.dp),
  cameraConstraints: CameraConstraints = CameraConstraints(),
  renderOptions: RenderOptions = RenderOptions.Standard,
  gestures: MapGestures = MapGestures.Standard,
  tileLodOptions: TileLodOptions = TileLodOptions.Standard,
  onClick: MapClickHandler? = null,
  onLongClick: MapClickHandler? = null,
  onDoubleClick: MapClickHandler? = null,
  onTwoFingerClick: MapClickHandler? = null,
  onUnhandledClick: MapClickHandler? = null,
  onPointerMove: ((HoverEvent) -> Unit)? = null,
  contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
  overlay: @Composable @UiComposable MapOverlayScope.() -> Unit = {
    include(MapOverlay.Default)
  },
) {
  if (LocalInspectionMode.current) {
    Box(modifier = modifier.fillMaxSize().background(Color.Gray))
    return
  }

  val presentationHostIdentity = mapPresentationHostIdentity()
  val presentationOwner = remember(state) { MapPresentationOwnerToken() }
  val mapViewOptions =
    MapViewOptions(
      cameraPadding = cameraPadding,
      cameraConstraints = cameraConstraints,
      renderOptions = renderOptions,
      gestures = gestures,
      tileLodOptions = tileLodOptions,
    )
  key(state, presentationHostIdentity) {
    PresentedMaplibreMap(
      state = state,
      presentationOwner = presentationOwner,
      modifier = modifier,
      mapViewOptions = mapViewOptions,
      handlers =
        MapClickHandlers(
          onClick,
          onLongClick,
          onDoubleClick,
          onTwoFingerClick,
          onUnhandledClick,
          onPointerMove,
        ),
      contentWindowInsets = contentWindowInsets,
      overlay = overlay,
    )
  }
}

@Composable
private fun PresentedMaplibreMap(
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  modifier: Modifier,
  mapViewOptions: MapViewOptions,
  handlers: MapClickHandlers,
  contentWindowInsets: WindowInsets,
  overlay: @Composable @UiComposable MapOverlayScope.() -> Unit,
) {
  val token = remember(state, presentationOwner) { state.reservePresentation(presentationOwner) }
  val attachment = remember(state, token) { MapStateAttachment(state, token) }
  DisposableEffect(attachment) { onDispose { attachment.release() } }
  MaplibreMapPresentation(
    state = state,
    attachment = attachment,
    modifier = modifier,
    mapViewOptions = mapViewOptions,
    handlers = handlers,
    contentWindowInsets = contentWindowInsets,
    overlay = overlay,
  )
}

@Composable
private fun MaplibreMapPresentation(
  state: MapState,
  attachment: MapStateAttachment,
  modifier: Modifier,
  mapViewOptions: MapViewOptions,
  handlers: MapClickHandlers,
  contentWindowInsets: WindowInsets,
  overlay: @Composable @UiComposable MapOverlayScope.() -> Unit,
) {
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
  val currentHandlers = rememberUpdatedState(handlers)
  val currentGestures = rememberUpdatedState(mapViewOptions.gestures)
  // The style subcomposition publishes into a revision state it re-creates per loaded style, and
  // the dispatcher must keep its identity because the pointer input holding it does not restart.
  val currentDesiredRevision = rememberUpdatedState(desiredRevisionState)
  val clickDispatcher =
    remember(state) {
      MapInteractionDispatcher(
        state = state,
        handlers = currentHandlers,
        desiredRevision = currentDesiredRevision,
        loadedStyle = rememberedStyleState,
        gestures = currentGestures,
      )
    }
  var retainedRevisionReplayed by remember(rememberedStyle, mapAttachment) { mutableStateOf(false) }

  LaunchedEffect(rememberedStyle, mapAttachment, attachment) {
    val map = mapAttachment?.adapter ?: return@LaunchedEffect
    if (rememberedStyle == null) return@LaunchedEffect
    try {
      map.replayStyleRevision(state.desiredStyleRevision)
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
          state.refreshStyleSources(map)
          clickDispatcher.presentationChanged(map)
        }

        override fun onEvent(map: MapAdapter, event: MapEvent) {
          state.onEvent(map, event)
          if (event is MapEvent.FrameRendered || event == MapEvent.Idle)
            clickDispatcher.presentationChanged(map)
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

  Box(modifier.fillMaxSize()) {
    ComposableMapView(
      modifier = Modifier.fillMaxSize(),
      state = state,
      style = state.style.baseStyle,
      update = update@{ map ->
          if (state.isClosed) return@update
          map.setCameraPadding(mapViewOptions.cameraPadding)
          map.setCameraConstraints(mapViewOptions.cameraConstraints)
          map.setRenderSettings(mapViewOptions.renderOptions)
          map.setTileLodSettings(mapViewOptions.tileLodOptions)
          attachment.publish(map)
        },
      onReset = {
        attachment.release()
        rememberedStyle = null
      },
      logger = state.runtime.logger,
      callbacks = adapterCallbacks,
      clicks = clickDispatcher,
      options = mapViewOptions,
    )

    MapOverlayHost(
      overlay = overlay,
      mapState = state,
      contentWindowInsets = contentWindowInsets,
      modifier = Modifier.matchParentSize().focusGroup(),
    )
  }
}
