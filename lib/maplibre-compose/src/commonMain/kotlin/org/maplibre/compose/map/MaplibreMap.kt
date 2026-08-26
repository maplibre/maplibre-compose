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
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MapOverlayHost
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.overlay.include
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.style.rememberStyleState
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.MapClickHandler
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * Displays a MapLibre based map.
 *
 * The map has no intrinsic size and will expand to fill its container by default. If placed in a
 * scrollable container or other layout that doesn't provide constraints, you must specify an
 * explicit size using modifiers like [Modifier.size][androidx.compose.foundation.layout.size].
 *
 * @param modifier The modifier to be applied to the layout.
 * @param baseStyle The URI or JSON of the map style to use. See
 *   [MapLibre Style](https://maplibre.org/maplibre-style-spec/).
 * @param cameraState The camera state specifies what position of the map is rendered, at what zoom,
 *   at what tilt, etc.
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
 * @param logger kermit logger to use.
 * @param onMapLoadFailed Invoked when the map failed to load.
 * @param onMapLoadFinished Invoked when the map finished loading.
 * @param contentWindowInsets Insets applied to [overlay]. Defaults to safe drawing insets.
 * @param overlay Controls drawn on top of the map. [MapOverlay.Default] draws the MapLibre logo and
 *   an attribution button; [MapOverlay.None] draws the map alone.
 *   [Modifier.placedAt][org.maplibre.compose.overlay.MapOverlayScope.placedAt] in the overlay pins
 *   Compose UI to a geographic position.
 * @param content The map content additional to what is already part of the map as defined in the
 *   base map style linked in [baseStyle].
 *
 * Additional [sources](https://maplibre.org/maplibre-style-spec/sources/) can be added via:
 * - [rememberGeoJsonSource][org.maplibre.compose.sources.rememberGeoJsonSource] (see
 *   [GeoJsonSource][org.maplibre.compose.sources.GeoJsonSource]),
 * - [rememberVectorSource][org.maplibre.compose.sources.rememberVectorSource] (see
 *   [VectorSource][org.maplibre.compose.sources.VectorSource]),
 * - [rememberCustomGeometrySource][org.maplibre.compose.sources.rememberCustomGeometrySource] (see
 *   [CustomGeometrySource][org.maplibre.compose.sources.CustomGeometrySource]),
 * - [rememberCustomVectorSource][org.maplibre.compose.sources.rememberCustomVectorSource] (see
 *   [CustomVectorSource][org.maplibre.compose.sources.CustomVectorSource]),
 * - [rememberRasterSource][org.maplibre.compose.sources.rememberRasterSource] (see
 *   [RasterSource][org.maplibre.compose.sources.RasterSource])
 * - [rememberRasterDemSource][org.maplibre.compose.sources.rememberRasterDemSource] (see
 *   [RasterDemSource][org.maplibre.compose.sources.RasterDemSource])
 *
 * A source that is already defined in the base map style can be referenced via
 * [getBaseSource][org.maplibre.compose.sources.getBaseSource].
 *
 * The data from a source can then be used in
 * [layer](https://maplibre.org/maplibre-style-spec/layers/) definition(s), which define how that
 * data is rendered, see:
 * - [BackgroundLayer][org.maplibre.compose.layers.BackgroundLayer]
 * - [ColorReliefLayer][org.maplibre.compose.layers.ColorReliefLayer]
 * - [LineLayer][org.maplibre.compose.layers.LineLayer]
 * - [FillExtrusionLayer][org.maplibre.compose.layers.FillExtrusionLayer]
 * - [FillLayer][org.maplibre.compose.layers.FillLayer]
 * - [HeatmapLayer][org.maplibre.compose.layers.HeatmapLayer]
 * - [HillshadeLayer][org.maplibre.compose.layers.HillshadeLayer]
 * - [LineLayer][org.maplibre.compose.layers.LineLayer]
 * - [RasterLayer][org.maplibre.compose.layers.RasterLayer]
 * - [SymbolLayer][org.maplibre.compose.layers.SymbolLayer]
 *
 * By default, the layers defined in this scope are put on top of the layers from the base style, in
 * the order they are defined. Alternatively, it is possible to anchor layers at certain layers from
 * the base style. This is done, for example, in order to add a layer just below the first symbol
 * layer from the base style so that it isn't above labels. See:
 * - [Anchor.Top][org.maplibre.compose.layers.Anchor.Companion.Top],
 * - [Anchor.Bottom][org.maplibre.compose.layers.Anchor.Companion.Bottom],
 * - [Anchor.Above][org.maplibre.compose.layers.Anchor.Companion.Above],
 * - [Anchor.Below][org.maplibre.compose.layers.Anchor.Companion.Below],
 * - [Anchor.Replace][org.maplibre.compose.layers.Anchor.Companion.Replace],
 * - [Anchor.At][org.maplibre.compose.layers.Anchor.Companion.At]
 */
@Composable
public fun MaplibreMap(
  modifier: Modifier = Modifier,
  baseStyle: BaseStyle = BaseStyle.Demo,
  cameraState: CameraState = rememberCameraState(),
  cameraPadding: PaddingValues = PaddingValues(0.dp),
  zoomRange: ClosedRange<Float> = 0f..20f,
  pitchRange: ClosedRange<Float> = 0f..60f,
  boundingBox: BoundingBox? = null,
  styleState: StyleState = rememberStyleState(),
  onMapClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  onMapLongClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  onFrame: (framesPerSecond: Double) -> Unit = {},
  options: MapOptions = MapOptions(),
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

  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val locals = currentCompositionLocalContext

  // Keyed on nothing: changed inputs update the state below rather than recreate it.
  val mapState = remember {
    MapState(cameraState, styleState, density, layoutDirection, logger, locals)
  }
  DisposableEffect(mapState) { onDispose { mapState.close() } }
  // Deferred past this snapshot's apply: the host would otherwise read records it cannot yet see.
  LaunchedEffect(mapState) { mapState.startStyleComposition() }

  mapState.updateStyleContent(content)

  // The holders are snapshot state, and a composition write to them can diverge from the plain
  // rewiring the setters perform, so the swap waits for the apply like every other setter here.
  SideEffect {
    mapState.baseStyle = baseStyle
    mapState.cameraState = cameraState
    mapState.styleState = styleState
  }

  // Keyed on the caller's overlay so the state overload sees a stable lambda per overlay.
  val overlayContent: @Composable @UiComposable MapOverlayScope.() -> Unit =
    remember(overlay) { { include(overlay) } }

  MaplibreMap(
    state = mapState,
    modifier = modifier,
    cameraPadding = cameraPadding,
    zoomRange = zoomRange,
    pitchRange = pitchRange,
    boundingBox = boundingBox,
    onMapClick = onMapClick,
    onMapLongClick = onMapLongClick,
    onFrame = onFrame,
    options = options,
    logger = logger,
    onMapLoadFailed = onMapLoadFailed,
    onMapLoadFinished = onMapLoadFinished,
    contentWindowInsets = contentWindowInsets,
    overlay = overlayContent,
  )
}

/**
 * Remembers a [MapState] that the composition owns: created once, and closed when the composition
 * leaves. Pass the returned state to [MaplibreMap].
 *
 * @param baseStyle The URI or JSON of the map style to use. Assigned to [MapState.baseStyle] on
 *   every recomposition. See [MapLibre Style](https://maplibre.org/maplibre-style-spec/).
 * @param styleContent The sources and layers composed over [baseStyle]; null sets no content. See
 *   [MapState.setStyleContent].
 */
@Composable
public fun rememberMapState(
  baseStyle: BaseStyle = BaseStyle.Demo,
  styleContent: (@Composable @MaplibreComposable () -> Unit)? = null,
): MapState {
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val locals = currentCompositionLocalContext

  // Keyed on nothing: changed inputs update the state below rather than recreate it.
  val mapState = remember {
    MapState(
      cameraState = CameraState(CameraPosition()),
      styleState = StyleState(),
      density = density,
      layoutDirection = layoutDirection,
      logger = Logger.withTag("maplibre-compose"),
      inheritedLocals = locals,
    )
  }
  DisposableEffect(mapState) { onDispose { mapState.close() } }
  // Deferred past this snapshot's apply: the host would otherwise read records it cannot yet see.
  LaunchedEffect(mapState) { mapState.startStyleComposition() }

  styleContent?.let(mapState::updateStyleContent)

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

  LaunchedEffect(state) { state.startStyleComposition() }

  // Written during composition: a session can attach in the same apply pass, before any SideEffect,
  // and the native core captures the logger when it is created.
  state.logger = logger

  SideEffect {
    // A state that never selected a style loads the default once a map shows it.
    state.baseStyle = state.baseStyle
    state.density = density
    state.layoutDirection = layoutDirection
    state.inheritedLocals = locals
    state.onMapClick = onMapClick
    state.onMapLongClick = onMapLongClick
    state.onFrame = onFrame
    state.onMapLoadFailed = onMapLoadFailed
    state.onMapLoadFinished = onMapLoadFinished
    state.clickScope = mapClickScope
  }

  val overlayHolder = remember(overlay) { MapOverlay(overlay) }

  Box(modifier.fillMaxSize()) {
    ComposableMapView(
      modifier = Modifier.fillMaxSize(),
      engine = state.engine,
      update = { map ->
        state.applyOptions(map, cameraPadding, zoomRange, pitchRange, boundingBox, options)
        state.attachSession(map)
      },
      onReset = { state.detachSession() },
      logger = logger,
      callbacks = state.callbacks,
      options = options,
    )

    MapOverlayHost(
      overlay = overlayHolder,
      cameraState = state.cameraState,
      styleState = state.styleState,
      contentWindowInsets = contentWindowInsets,
      modifier = Modifier.matchParentSize(),
    )
  }
}
