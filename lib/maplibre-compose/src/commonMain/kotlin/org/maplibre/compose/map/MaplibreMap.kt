package org.maplibre.compose.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MapOverlayHost
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleLayer
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleComposition
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.style.rememberStyleComposition
import org.maplibre.compose.style.rememberStyleState
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.MapClickHandler
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

private class MapStateAttachment(
  val state: MapState,
  private val token: MapPresentationToken,
) {
  val runtime: RuntimeImplementation
    get() = state.runtime

  fun publish(map: MapAdapter, options: MapPresentationOptions) {
    state.publishPresentation(token, map, options)
  }

  fun currentPresentation(map: MapAdapter): MapPresentation? =
    state.presentation?.takeIf { it.adapter === map }

  fun release(map: MapAdapter? = null) {
    state.releasePresentation(token, map)
  }

  fun markStyleReady(map: MapAdapter) {
    state.markStyleReady(map)
  }

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

private val LocalMapStateAttachment = staticCompositionLocalOf<MapStateAttachment?> { null }
private val LocalStyleComposition = staticCompositionLocalOf<StyleComposition?> { null }

/**
 * Displays [state] through one temporary presentation.
 *
 * The caller keeps [state] alive. This composable creates only the current presentation and
 * releases it when the call leaves composition.
 */
@Composable
public fun MaplibreMap(
  state: MapState = rememberMapState(),
  styleComposition: StyleComposition = StyleComposition.Empty,
  modifier: Modifier = Modifier,
  presentationOptions: MapPresentationOptions = MapPresentationOptions(),
  callbacks: MapPresentationCallbacks = MapPresentationCallbacks(),
  contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
  overlay: MapOverlay = MapOverlay.Default,
) {
  val presentationHostIdentity = mapPresentationHostIdentity()
  val presentationOwner = remember(state) { MapPresentationOwnerToken() }
  key(state, presentationHostIdentity) {
    PresentedMaplibreMap(
      state = state,
      presentationOwner = presentationOwner,
      styleComposition = styleComposition,
      modifier = modifier,
      presentationOptions = presentationOptions,
      callbacks = callbacks,
      contentWindowInsets = contentWindowInsets,
      overlay = overlay,
    )
  }
}

@Composable
private fun PresentedMaplibreMap(
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  styleComposition: StyleComposition,
  modifier: Modifier,
  presentationOptions: MapPresentationOptions,
  callbacks: MapPresentationCallbacks,
  contentWindowInsets: WindowInsets,
  overlay: MapOverlay,
) {
  val token = remember(state, presentationOwner) { state.reservePresentation(presentationOwner) }
  val attachment = remember(state, token) { MapStateAttachment(state, token) }
  DisposableEffect(attachment) { onDispose { attachment.release() } }
  CompositionLocalProvider(
    LocalMapStateAttachment provides attachment,
    LocalStyleComposition provides styleComposition,
  ) {
    MaplibreMapPresentation(
      modifier = modifier,
      baseStyle = state.style.baseStyle,
      cameraState = state.cameraState,
      styleState = state.compatibilityStyleState,
      cameraPadding = presentationOptions.cameraPadding,
      zoomRange = presentationOptions.zoomRange,
      pitchRange = presentationOptions.pitchRange,
      boundingBox = presentationOptions.boundingBox,
      onMapClick = callbacks.onClick,
      onMapLongClick = callbacks.onLongClick,
      onFrame = callbacks.onFrame,
      options = presentationOptions,
      logger = state.runtime.logger,
      contentWindowInsets = contentWindowInsets,
      overlay = overlay,
    )
  }
}

/** Internal host for the current map presentation while callers migrate through [MaplibreMap]. */
@Composable
private fun MaplibreMapPresentation(
  modifier: Modifier = Modifier,
  baseStyle: BaseStyle = BaseStyle.Demo,
  cameraState: CameraState,
  cameraPadding: PaddingValues = PaddingValues(0.dp),
  zoomRange: ClosedRange<Float> = 0f..20f,
  pitchRange: ClosedRange<Float> = 0f..60f,
  boundingBox: BoundingBox? = null,
  styleState: StyleState = rememberStyleState(),
  onMapClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  onMapLongClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  onFrame: (framesPerSecond: Double) -> Unit = {},
  options: MapPresentationOptions = MapPresentationOptions(),
  logger: Logger? = remember { Logger.withTag("maplibre-compose") },
  onMapLoadFailed: (reason: String?) -> Unit = {},
  onMapLoadFinished: () -> Unit = {},
  contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
  overlay: MapOverlay = MapOverlay.Default,
  content: @Composable @MaplibreComposable () -> Unit = {},
) {
  // In preview/inspection mode, show a placeholder instead of trying to render the map
  if (LocalInspectionMode.current) {
    Box(modifier = modifier.fillMaxSize().background(Color.Gray))
    return
  }

  val stateAttachment = LocalMapStateAttachment.current
  val suppliedStyleComposition = LocalStyleComposition.current
  var rememberedStyle by remember { mutableStateOf<StyleBinding?>(null) }
  val legacyStyleComposition = remember(content) { StyleComposition(content) }
  val evaluatedComposition = suppliedStyleComposition ?: legacyStyleComposition
  val desiredRevision by
    rememberStyleComposition(
      composition = evaluatedComposition,
      maybeStyle = rememberedStyle,
      replaceableSourceIds =
        stateAttachment?.state?.desiredStyleRevision?.sources?.mapTo(mutableSetOf()) { it.id }
          ?: emptySet(),
      replaceableLayerIds =
        stateAttachment?.state?.desiredStyleRevision?.layers?.mapTo(mutableSetOf()) {
          it.definition.id
        } ?: emptySet(),
      styleState = styleState,
    )
  val mapClickScope = rememberCoroutineScope()
  val presentation = stateAttachment?.state?.presentation
  var retainedRevisionReplayed by
    remember(rememberedStyle, presentation) { mutableStateOf(stateAttachment == null) }

  LaunchedEffect(rememberedStyle, presentation, stateAttachment) {
    val attachment = stateAttachment ?: return@LaunchedEffect
    val map = presentation?.adapter ?: return@LaunchedEffect
    if (rememberedStyle == null) return@LaunchedEffect
    try {
      map.replayStyleRevision(attachment.state.desiredStyleRevision)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      logger?.w(error) { "Could not replay the retained style revision" }
    } finally {
      retainedRevisionReplayed = true
    }
  }

  LaunchedEffect(rememberedStyle, desiredRevision, presentation, retainedRevisionReplayed) {
    if (!retainedRevisionReplayed) return@LaunchedEffect
    val map = presentation?.adapter ?: cameraState.map ?: return@LaunchedEffect
    val revision = desiredRevision ?: return@LaunchedEffect
    stateAttachment?.reconcileStyleRevision(map, revision) ?: map.reconcileStyleRevision(revision)
    styleState.refreshSources()
  }

  val adapterCallbacks =
    remember(
      cameraState,
      styleState,
      desiredRevision,
      mapClickScope,
      stateAttachment,
      presentation,
      options,
    ) {
      object : MapAdapter.Callbacks {
        private fun currentPresentation(map: MapAdapter): MapPresentation? =
          stateAttachment?.currentPresentation(map) ?: presentation?.takeIf { it.adapter === map }

        private fun synchronizeCamera(map: MapAdapter): MapPresentation? {
          if (cameraState.map !== map) return null
          val viewport = map.getViewport()
          cameraState.positionState.value = map.getCameraPosition()
          if (viewport == null) return null
          return currentPresentation(map)?.also { it.cameraMoved(viewport) }
        }

        override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
          rememberedStyle = style
          synchronizeCamera(map)
        }

        override fun onMapFailLoading(map: MapAdapter, reason: String?) {
          stateAttachment?.markStyleFailed(map, reason)
          onMapLoadFailed(reason)
        }

        override fun onMapFinishedLoading(map: MapAdapter) {
          stateAttachment?.markStyleReady(map)
          styleState.refreshSources()
          onMapLoadFinished()
        }

        override fun onSourceChanged(map: MapAdapter, sourceId: String?) {
          if (sourceId == null) styleState.refreshSources() else styleState.refreshSource(sourceId)
        }

        override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) {
          if (cameraState.map !== map) return
          currentPresentation(map)?.cameraMoveStarted(reason)
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

        override fun onClick(map: MapAdapter, latLng: Position, offset: DpOffset) {
          if (currentPresentation(map) == null) return
          if (onMapClick(latLng, offset).consumed) return
          mapClickScope.launch {
            for (node in layerNodesInOrder()) {
              if (node.onClick == null) continue
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
                layerNodesInOrder().firstOrNull { it.definition.id == node.definition.id }?.onClick
                  ?: continue
              if (features.isNotEmpty() && currentHandle(features).consumed) break
            }
          }
        }

        override fun onLongClick(map: MapAdapter, latLng: Position, offset: DpOffset) {
          if (currentPresentation(map) == null) return
          if (onMapLongClick(latLng, offset).consumed) return
          mapClickScope.launch {
            for (node in layerNodesInOrder()) {
              if (node.onLongClick == null) continue
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
                  ?.onLongClick ?: continue
              if (features.isNotEmpty() && currentHandle(features).consumed) break
            }
          }
        }

        override fun onFrame(fps: Double) {
          val map = cameraState.map ?: return
          if (currentPresentation(map) == null) return
          synchronizeCamera(map)
          onFrame(fps)
        }
      }
    }

  Box(modifier.fillMaxSize()) {
    ComposableMapView(
      modifier = Modifier.fillMaxSize(),
      runtime = stateAttachment?.runtime,
      state = stateAttachment?.state,
      style = baseStyle,
      update = update@{ map ->
          if (stateAttachment?.state?.isClosed == true) return@update
          map.setCameraPadding(cameraPadding)
          map.setCameraConstraints(
            CameraConstraints(
              minZoom = zoomRange.start.toDouble(),
              maxZoom = zoomRange.endInclusive.toDouble(),
              minPitch = pitchRange.start.toDouble(),
              maxPitch = pitchRange.endInclusive.toDouble(),
              boundingBox = boundingBox,
            )
          )
          map.setRenderSettings(options.renderOptions)
          map.setGestureSettings(options.gestureOptions)
          map.setTileLodSettings(options.tileLodOptions)
          cameraState.map = map
          stateAttachment?.publish(map, options)
        },
      onReset = {
        stateAttachment?.release(cameraState.map)
        cameraState.map = null
        rememberedStyle = null
      },
      logger = logger,
      callbacks = adapterCallbacks,
      rememberedStyle = rememberedStyle,
      options = options,
    )

    stateAttachment?.state?.let { state ->
      MapOverlayHost(
        overlay = overlay,
        mapState = state,
        presentation = presentation,
        styleState = styleState,
        contentWindowInsets = contentWindowInsets,
        modifier = Modifier.matchParentSize(),
      )
    }
  }
}
