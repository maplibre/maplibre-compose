package org.maplibre.compose.map

import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MapOverlayHost
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.overlay.include
import org.maplibre.compose.style.DesiredStyleLayer
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.rememberStyleComposition
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MapClickHandler
import org.maplibre.spatialk.geojson.Position

private class MapStateAttachment(
  val state: MapState,
  private val token: MapPresentationToken,
) {
  fun publish(map: MapAdapter) {
    state.publishPresentation(token, map)
  }

  fun currentAttachment(map: MapAdapter): MapAttachment? =
    state.currentMapAttachment?.takeIf { it.adapter === map }

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
      if (map.reconcileStyleRevision(revision)) state.markStyleReady(map)
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
 */
@Composable
public fun MaplibreMap(
  modifier: Modifier = Modifier,
  state: MapState = rememberMapState(),
  cameraPadding: PaddingValues = PaddingValues(0.dp),
  cameraConstraints: CameraConstraints = CameraConstraints(),
  renderOptions: RenderOptions = RenderOptions.Standard,
  gestureOptions: GestureOptions = GestureOptions.Standard,
  tileLodOptions: TileLodOptions = TileLodOptions.Standard,
  onClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  onLongClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  onFrame: (framesPerSecond: Double) -> Unit = {},
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
      gestureOptions = gestureOptions,
      tileLodOptions = tileLodOptions,
    )
  key(state, presentationHostIdentity) {
    PresentedMaplibreMap(
      state = state,
      presentationOwner = presentationOwner,
      modifier = modifier,
      mapViewOptions = mapViewOptions,
      onClick = onClick,
      onLongClick = onLongClick,
      onFrame = onFrame,
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
  onClick: MapClickHandler,
  onLongClick: MapClickHandler,
  onFrame: (Double) -> Unit,
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
    onClick = onClick,
    onLongClick = onLongClick,
    onFrame = onFrame,
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
  onClick: MapClickHandler,
  onLongClick: MapClickHandler,
  onFrame: (Double) -> Unit,
  contentWindowInsets: WindowInsets,
  overlay: @Composable @UiComposable MapOverlayScope.() -> Unit,
) {
  var rememberedStyle by remember { mutableStateOf<StyleBinding?>(null) }
  val desiredRevision by
    rememberStyleComposition(
      composition = state.styleComposition,
      maybeStyle = rememberedStyle,
      mapState = state,
      replaceableSourceIds = state.desiredStyleRevision.sources.mapTo(mutableSetOf()) { it.id },
      replaceableLayerIds =
        state.desiredStyleRevision.layers.mapTo(mutableSetOf()) {
          it.definition.id
        },
    )
  val mapClickScope = rememberCoroutineScope()
  val mapAttachment = state.currentMapAttachment
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
    remember(
      desiredRevision,
      mapClickScope,
      attachment,
      mapAttachment,
      onClick,
      onLongClick,
      onFrame,
    ) {
      object : MapAdapter.Callbacks {
        private fun currentAttachment(map: MapAdapter): MapAttachment? =
          attachment.currentAttachment(map)

        private fun synchronizeCamera(map: MapAdapter): MapAttachment? {
          return state.synchronizeCamera(map)
        }

        override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
          if (!state.updateLoadedStyle(map, style)) return
          rememberedStyle = style
          synchronizeCamera(map)
        }

        override fun onMapFailLoading(map: MapAdapter, reason: String?) {
          attachment.markStyleFailed(map, reason)
        }

        override fun onMapFinishedLoading(map: MapAdapter) {
          attachment.markStyleReady(map)
        }

        override fun onSourceChanged(map: MapAdapter, sourceId: String?) {
          state.refreshStyleSources(map)
        }

        override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) {
          currentAttachment(map)?.cameraMoveStarted(reason)
        }

        override fun onCameraMoved(map: MapAdapter) {
          synchronizeCamera(map)
        }

        override fun onCameraMoveEnded(map: MapAdapter) {
          synchronizeCamera(map)?.cameraMoveEnded()
        }

        private fun layerNodesInOrder(): List<DesiredStyleLayer> {
          val layerNodes = desiredRevision?.layers?.associateBy { it.definition.id }.orEmpty()
          val layers = rememberedStyle?.getLayers().orEmpty()
          return layers.asReversed().mapNotNull { layer -> layerNodes[layer.id] }
        }

        private fun dispatchPointerEvent(
          map: MapAdapter,
          latLng: Position,
          offset: DpOffset,
          mapHandler: MapClickHandler,
          layerHandler: (DesiredStyleLayer) -> FeaturesClickHandler?,
        ) {
          if (currentAttachment(map) == null) return
          if (mapHandler(latLng, offset).consumed) return
          mapClickScope.launch {
            for (node in layerNodesInOrder()) {
              if (layerHandler(node) == null) continue
              val features =
                map.queryRenderedFeatures(
                  offset = offset,
                  layerIds = setOf(node.definition.id),
                  predicate = null,
                )
              // Recomposition may replace or remove the node while the query is suspended. A
              // removed node never receives the click; a replaced one answers with the handler
              // it has now.
              val currentHandle =
                layerNodesInOrder()
                  .firstOrNull { it.definition.id == node.definition.id }
                  ?.let(layerHandler) ?: continue
              if (features.isNotEmpty() && currentHandle(features).consumed) break
            }
          }
        }

        override fun onClick(map: MapAdapter, latLng: Position, offset: DpOffset) =
          dispatchPointerEvent(map, latLng, offset, onClick, DesiredStyleLayer::onClick)

        override fun onLongClick(map: MapAdapter, latLng: Position, offset: DpOffset) =
          dispatchPointerEvent(
            map,
            latLng,
            offset,
            onLongClick,
            DesiredStyleLayer::onLongClick,
          )

        override fun onFrame(fps: Double) {
          val map = state.currentMapAttachment?.adapter ?: return
          synchronizeCamera(map)
          onFrame(fps)
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
          map.setGestureSettings(mapViewOptions.gestureOptions)
          map.setTileLodSettings(mapViewOptions.tileLodOptions)
          attachment.publish(map)
        },
      onReset = {
        attachment.release()
        rememberedStyle = null
      },
      logger = state.runtime.logger,
      callbacks = adapterCallbacks,
      options = mapViewOptions,
    )

    MapOverlayHost(
      overlay = overlay,
      mapState = state,
      contentWindowInsets = contentWindowInsets,
      modifier = Modifier.matchParentSize(),
    )
  }
}
