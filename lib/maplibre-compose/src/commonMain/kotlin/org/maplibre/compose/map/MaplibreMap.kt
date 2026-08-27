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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraPositionSaver
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MapOverlayHost
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.overlay.include
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.MapClickHandler
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * Remembers a [MapState] that the composition owns: created once, and closed when the composition
 * leaves. Pass the returned state to [MaplibreMap].
 *
 * @param cameraPosition The first camera position. The camera position saves across recreation with
 *   `rememberSaveable`, and a recreated composition starts the camera at the saved position instead
 *   of this one.
 * @param baseStyle The URI or JSON of the map style to use. Assigned to [MapState.baseStyle] on
 *   every recomposition. See [MapLibre Style](https://maplibre.org/maplibre-style-spec/).
 * @param styleContent The sources and layers composed over [baseStyle]; null composes no content,
 *   so a recomposition that passes null after content clears that content from the map. Inside the
 *   content, [LocalMapState] returns the returned state. See [MapState.setStyleContent].
 */
@Composable
public fun rememberMapState(
  cameraPosition: CameraPosition = CameraPosition(),
  baseStyle: BaseStyle = BaseStyle.Demo,
  styleContent: (@Composable @MaplibreComposable () -> Unit)? = null,
): MapState {
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val locals = currentCompositionLocalContext

  val newMapState = { position: CameraPosition ->
    MapState(
      cameraPosition = position,
      density = density,
      layoutDirection = layoutDirection,
      logger = Logger.withTag("maplibre-compose"),
      inheritedLocals = locals,
    )
  }

  // The camera is the saveable piece: the saver reads the state's current position, and a
  // recreated composition constructs the state at the restored one. Keyed on nothing: changed
  // inputs update the state below rather than recreate it.
  val mapState =
    rememberSaveable(
      saver =
        Saver(
          save = { state -> with(CameraPositionSaver) { save(state.camera) } },
          restore = { saved -> newMapState(CameraPositionSaver.restore(saved)) },
        )
    ) {
      newMapState(cameraPosition)
    }
  DisposableEffect(mapState) { onDispose { mapState.close() } }
  // Deferred past this snapshot's apply: the host would otherwise read records it cannot yet see.
  LaunchedEffect(mapState) { mapState.startStyleComposition() }

  if (styleContent != null) mapState.updateStyleContent(styleContent)
  else mapState.clearStyleContent()

  SideEffect { mapState.baseStyle = baseStyle }

  return mapState
}

/**
 * Displays the map that [state] holds.
 *
 * The composable is a render session on the state: it draws the map, feeds gestures into it, and
 * draws [overlay] on top. The style and its content belong to [state], which survives this
 * composable leaving the composition; get one from [rememberMapState], or construct a [MapState]
 * outside the composition and own its lifetime.
 *
 * The map has no intrinsic size and will expand to fill its container by default. If placed in a
 * scrollable container or other layout that doesn't provide constraints, you must specify an
 * explicit size using modifiers like [Modifier.size][androidx.compose.foundation.layout.size].
 *
 * @param state The map to display: its base style, style content, and camera.
 * @param modifier The modifier to be applied to the layout.
 * @param cameraPadding Insets that shift the camera center. A bounds move adds its padding to these
 *   insets.
 * @param zoomRange The allowable camera zoom range.
 * @param pitchRange The allowable camera pitch range.
 * @param boundingBox The allowable bounds for the camera position. On iOS and Web, it prevents the
 *   camera **edges** from going out of bounds. If null is provided, the bounds are reset. On
 *   Android, it prevents the camera **center** from going out of bounds. See
 *   [this GH Issue](https://github.com/maplibre/maplibre-native/issues/3128).
 * @param onMapClick Invoked when the map is clicked. A click callback can be defined per layer,
 *   too, see e.g. the `onClick` parameter for [LineLayer][org.maplibre.compose.layers.LineLayer].
 *   However, this callback is always called first and can thus prevent subsequent callbacks to be
 *   invoked by consuming the event.
 * @param onMapLongClick Invoked when the map is long-clicked. See [onMapClick].
 * @param onFrame Invoked on every rendered frame.
 * @param options Gesture, render, and tile level-of-detail options for this session.
 * @param logger kermit logger to use.
 * @param onMapLoadFailed Invoked when the map failed to load.
 * @param onMapLoadFinished Invoked when the map finished loading.
 * @param contentWindowInsets Insets applied to [overlay]. Defaults to safe drawing insets.
 * @param overlay Controls drawn on top of the map; the default draws
 *   [MapOverlay.Default][org.maplibre.compose.overlay.MapOverlay.Companion.Default].
 *   [Modifier.placedAt][org.maplibre.compose.overlay.MapOverlayScope.placedAt] in the overlay pins
 *   Compose UI to a geographic position. Style content does not go here; it goes to [state] through
 *   [rememberMapState] or [MapState.setStyleContent].
 */
@Composable
public fun MaplibreMap(
  state: MapState,
  modifier: Modifier = Modifier,
  cameraPadding: PaddingValues = PaddingValues(0.dp),
  zoomRange: ClosedRange<Float> = 0f..20f,
  pitchRange: ClosedRange<Float> = 0f..60f,
  boundingBox: BoundingBox? = null,
  onMapClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  onMapLongClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  onFrame: (framesPerSecond: Double) -> Unit = {},
  options: MapOptions = MapOptions(),
  logger: Logger? = remember { Logger.withTag("maplibre-compose") },
  onMapLoadFailed: (reason: String?) -> Unit = {},
  onMapLoadFinished: () -> Unit = {},
  contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
  overlay: @Composable @UiComposable MapOverlayScope.() -> Unit = { include(MapOverlay.Default) },
) {
  // In preview/inspection mode, show a placeholder instead of trying to render the map
  if (LocalInspectionMode.current) {
    Box(modifier = modifier.fillMaxSize().background(Color.Gray))
    return
  }

  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val locals = currentCompositionLocalContext
  val mapClickScope = rememberCoroutineScope()

  // Written during composition: a session can attach in the same apply pass, before any SideEffect,
  // and the native core captures the logger when it is created.
  state.logger = logger

  SideEffect {
    state.ensureBaseStyleSelected()
    state.density = density
    state.layoutDirection = layoutDirection
    state.inheritedLocals = locals
    state.callbacks.onMapClick = onMapClick
    state.callbacks.onMapLongClick = onMapLongClick
    state.callbacks.onFrame = onFrame
    state.callbacks.onMapLoadFailed = onMapLoadFailed
    state.callbacks.onMapLoadFinished = onMapLoadFinished
    state.callbacks.clickScope = mapClickScope
  }

  val overlayHolder = remember(overlay) { MapOverlay(overlay) }

  Box(modifier.fillMaxSize()) {
    ComposableMapView(
      modifier = Modifier.fillMaxSize(),
      engine = state.engine,
      update = { map ->
        map.applyOptions(cameraPadding, zoomRange, pitchRange, boundingBox, options)
        state.attachSession(map)
      },
      onReset = { state.detachSession() },
      logger = logger,
      callbacks = state.callbacks,
      options = options,
    )

    MapOverlayHost(
      overlay = overlayHolder,
      state = state,
      contentWindowInsets = contentWindowInsets,
      modifier = Modifier.matchParentSize(),
    )
  }
}

/** Applies the composable's per-composition options; attach-independent, safe to repeat. */
private fun MapAdapter.applyOptions(
  cameraPadding: PaddingValues,
  zoomRange: ClosedRange<Float>,
  pitchRange: ClosedRange<Float>,
  boundingBox: BoundingBox?,
  options: MapOptions,
) {
  setCameraPadding(cameraPadding)
  setMinZoom(zoomRange.start.toDouble())
  setMaxZoom(zoomRange.endInclusive.toDouble())
  setMinPitch(pitchRange.start.toDouble())
  setMaxPitch(pitchRange.endInclusive.toDouble())
  setRenderSettings(options.renderOptions)
  setTileLodSettings(options.tileLodOptions)
  setCameraBoundingBox(boundingBox)
}
