package org.maplibre.compose.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.LayoutDirection
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
 * @param initialCameraPosition Sets the camera once, when the state is created; a recreated
 *   composition restores the saved camera position instead.
 * @param baseStyle The URI or JSON of the map style to use. Assigned to [MapState.baseStyle] on
 *   every recomposition. See [MapLibre Style](https://maplibre.org/maplibre-style-spec/).
 * @param styleComposition The sources and layers composed over [baseStyle]; null composes nothing,
 *   and clears a composition that an earlier recomposition passed. Inside the composition,
 *   [LocalMapState] returns the returned state. See [MapState.setStyleComposition].
 */
@Composable
public fun rememberMapState(
  initialCameraPosition: CameraPosition = CameraPosition(),
  baseStyle: BaseStyle = BaseStyle.Demo,
  styleComposition: (@Composable @MaplibreComposable () -> Unit)? = null,
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
      newMapState(initialCameraPosition)
    }
  DisposableEffect(mapState) { onDispose { mapState.close() } }
  // Deferred past this snapshot's apply: the host would otherwise read records it cannot yet see.
  LaunchedEffect(mapState) { mapState.startStyleComposition() }

  if (styleComposition != null) mapState.updateStyleComposition(styleComposition)
  else mapState.clearStyleComposition()

  SideEffect { mapState.baseStyle = baseStyle }

  return mapState
}

/**
 * Displays the map that [state] holds.
 *
 * The composable is a render session on the state: it draws the map, feeds gestures into it, and
 * draws [overlay] on top. The style and its composition belong to [state], which survives this
 * composable leaving the composition; get one from [rememberMapState], or construct a [MapState]
 * outside the composition and own its lifetime. The session is keyed on [state], so a recomposition
 * that passes a different state disposes this session and composes a new one for that state.
 *
 * The map has no intrinsic size and will expand to fill its container by default. If placed in a
 * scrollable container or other layout that doesn't provide constraints, you must specify an
 * explicit size using modifiers like [Modifier.size][androidx.compose.foundation.layout.size].
 *
 * @param state The map to display: its base style, style composition, and camera.
 * @param cameraPadding Insets that shift the camera center. Null follows [contentWindowInsets],
 *   resolved against the current layout direction. A bounds move adds its padding to these insets.
 * @param zoomRange The camera zoom range that gestures and camera calls stay within.
 * @param pitchRange The camera pitch range that gestures and camera calls stay within.
 * @param boundingBox The allowable bounds for the camera position. On iOS and Web, it prevents the
 *   camera **edges** from going out of bounds. If null is provided, the bounds are reset. On
 *   Android, it prevents the camera **center** from going out of bounds. See
 *   [this GH Issue](https://github.com/maplibre/maplibre-native/issues/3128).
 * @param onMapClick Invoked when the map is clicked, before any per-layer click callback such as
 *   [LineLayer][org.maplibre.compose.layers.LineLayer]'s `onClick`. Consuming the event here stops
 *   the per-layer callbacks.
 * @param onMapLongClick Invoked when the map is long-clicked. See [onMapClick].
 * @param onFrame Invoked on every rendered frame with the current frame rate.
 * @param options Gesture, render, and tile level-of-detail options for this session.
 * @param contentWindowInsets Insets applied to [overlay].
 * @param overlay Controls drawn on top of the map.
 *   [Modifier.placedAt][org.maplibre.compose.overlay.MapOverlayScope.placedAt] in the overlay pins
 *   Compose UI to a geographic position. Style composition does not go here; it goes to [state]
 *   through [rememberMapState] or [MapState.setStyleComposition].
 */
@Composable
public fun MaplibreMap(
  state: MapState = rememberMapState(),
  modifier: Modifier = Modifier,
  cameraPadding: PaddingValues? = null,
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

  // A different state is a different map: the old state's session subtree is disposed, and a new
  // one composes for the new state.
  key(state) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val locals = currentCompositionLocalContext
    val mapClickScope = rememberCoroutineScope()

    // Reading each side during composition recomposes this map when the insets change, so the
    // session receives the new padding. Resolving against the current layout direction here makes a
    // direction flip change the captured SessionOptions, so directional padding reapplies.
    val insetPadding = contentWindowInsets.asPaddingValues()
    val resolvedCameraPadding = (cameraPadding ?: insetPadding).resolveAbsolute(layoutDirection)

    // Written during composition: a session can attach in the same apply pass, before any
    // SideEffect, and the native core captures the logger when it is created.
    state.logger = logger

    SideEffect {
      state.ensureBaseStyleSelected()
      state.density = density
      state.layoutDirection = layoutDirection
      state.inheritedLocals = locals
      state.sessionOptions =
        SessionOptions(resolvedCameraPadding, zoomRange, pitchRange, boundingBox, options)
      state.callbacks.onMapClick = onMapClick
      state.callbacks.onMapLongClick = onMapLongClick
      state.callbacks.onFrame = onFrame
      state.callbacks.onMapLoadFailed = onMapLoadFailed
      state.callbacks.onMapLoadFinished = onMapLoadFinished
      state.callbacks.clickScope = mapClickScope
    }

    val overlayHolder = remember(overlay) { MapOverlay(overlay) }

    Box(modifier.fillMaxSize()) {
      ComposableMapView(state = state, modifier = Modifier.fillMaxSize(), options = options)

      MapOverlayHost(
        overlay = overlayHolder,
        state = state,
        contentWindowInsets = contentWindowInsets,
        modifier = Modifier.matchParentSize(),
      )
    }
  }
}

/** Resolves [this] into absolute sides so the captured padding no longer depends on direction. */
internal fun PaddingValues.resolveAbsolute(direction: LayoutDirection): PaddingValues.Absolute =
  PaddingValues.Absolute(
    left = calculateLeftPadding(direction),
    top = calculateTopPadding(),
    right = calculateRightPadding(direction),
    bottom = calculateBottomPadding(),
  )

/** The composable's per-session options; attach-independent, safe to repeat on a live map. */
internal data class SessionOptions(
  val cameraPadding: PaddingValues,
  val zoomRange: ClosedRange<Float>,
  val pitchRange: ClosedRange<Float>,
  val boundingBox: BoundingBox?,
  val options: MapOptions,
) {
  fun applyTo(map: MapAdapter) {
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
    map.setTileLodSettings(options.tileLodOptions)
  }
}
