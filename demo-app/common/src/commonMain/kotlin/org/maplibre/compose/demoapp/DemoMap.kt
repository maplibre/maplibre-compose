package org.maplibre.compose.demoapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.filter_center_focus_24px
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.material3.Material3
import org.maplibre.compose.material3.Material3Full
import org.maplibre.compose.material3.PointerPinButton
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.spatialk.geojson.Position

/** How long the camera takes to fly to a newly selected demo. */
val DemoFlightDuration = 2.seconds

/** Padding between fitted bounds and the edge of the map viewport. */
val DemoBoundsPadding = PaddingValues(48.dp)

internal suspend fun MapState.flyTo(destination: DemoDestination) {
  when (destination) {
    is DemoDestination.ExactCamera ->
      animateCameraPosition(
        position = destination.position,
        duration = DemoFlightDuration,
      )
    is DemoDestination.FitBounds ->
      animateCameraToBounds(
        boundingBox = destination.bounds,
        padding = DemoBoundsPadding,
        duration = DemoFlightDuration,
      )
    DemoDestination.None -> Unit
  }
}

/** The map controls the settings ask for. */
fun demoMapOverlay(settings: DemoSettings): MapOverlay =
  when {
    settings.useMaterial3Controls && settings.showZoomButtons -> MapOverlay.Material3Full
    settings.useMaterial3Controls -> MapOverlay.Material3
    settings.showZoomButtons -> MapOverlay.Full
    else -> MapOverlay.Default
  }

/**
 * The shared map, the selected demo's overlay, the pointer pin, and the diagnostic overlays.
 *
 * [overlay] holds the map controls; a shell with little room, such as a watch, passes a smaller set
 * than the settings ask for.
 */
@Composable
fun DemoMap(
  state: DemoAppState,
  viewportInsets: MapViewportInsets,
  overlay: MapOverlay = demoMapOverlay(state.settings),
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val appliedBase = state.appliedStyle.base
  val selectedDemo = state.selectedDemo
  LaunchedEffect(state.mapState.style.loadState, appliedBase) {
    when (state.mapState.style.loadState) {
      StyleLoadState.Ready,
      is StyleLoadState.Failed -> state.noteStyleLoad(appliedBase)
      StyleLoadState.Loading,
      StyleLoadState.Pending -> Unit
    }
  }
  LaunchedEffect(state.mapState) {
    withContext(Dispatchers.Default) {
      state.mapState.events.collect {
        if (it is MapEvent.FrameRendered) state.frameRateState.record()
      }
    }
  }
  val pointerPin = selectedDemo?.pointerPin
  val placementPadding =
    PaddingValues.Absolute(
      left = viewportInsets.left + MapOverlay.Spacing,
      top = viewportInsets.top + MapOverlay.Spacing,
      right = viewportInsets.right + MapOverlay.Spacing,
      bottom = viewportInsets.bottom + MapOverlay.Spacing,
    )
  Box(Modifier.fillMaxSize()) {
    MaplibreMap(
      state = state.mapState,
      modifier = modifier,
      cameraPadding = viewportInsets.asPaddingValues(),
      renderOptions = state.settings.renderOptions,
      gestureOptions = state.settings.gestureOptions,
      tileLodOptions = state.settings.tileLodOptions,
      contentWindowInsets = viewportInsets.asWindowInsets(),
    ) {
      include(overlay)
      selectedDemo?.let { demo ->
        key(demo) {
          with(demo) { Overlay(state) }
          pointerPin?.let {
            PointerPinButton(
              targetPosition = it.target,
              onClick = { scope.launch { state.mapState.flyTo(it.destination) } },
            ) {
              Icon(
                vectorResource(Res.drawable.filter_center_focus_24px),
                contentDescription = "Fly back to ${demo.name}",
              )
            }
          }
        }
      }
    }

    if (state.settings.showPointerPinDiagnostics && pointerPin != null) {
      PointerPinDestinationOverlay(
        mapState = state.mapState,
        destination = pointerPin.destination,
        modifier = Modifier.fillMaxSize(),
      )
      PointerPinPlacementOverlay(
        mapState = state.mapState,
        target = pointerPin.target,
        placementPadding = placementPadding,
        modifier = Modifier.fillMaxSize(),
      )
    }

    Box(Modifier.fillMaxSize().padding(placementPadding)) {
      DiagnosticOverlays(state = state, modifier = Modifier.align(Alignment.TopCenter))
    }
  }
}

@Composable
private fun PointerPinDestinationOverlay(
  mapState: MapState,
  destination: DemoDestination,
  modifier: Modifier = Modifier,
) {
  // The viewport read recomputes the projection when the camera changes.
  val viewport = mapState.viewport
  val projected =
    remember(destination, viewport) {
      when (destination) {
        is DemoDestination.ExactCamera ->
          mapState.screenLocationFromPosition(destination.position.target)?.let(::listOf)
        is DemoDestination.FitBounds -> {
          val bounds = destination.bounds
          listOf(
              Position(longitude = bounds.west, latitude = bounds.north),
              Position(longitude = bounds.east, latitude = bounds.north),
              Position(longitude = bounds.east, latitude = bounds.south),
              Position(longitude = bounds.west, latitude = bounds.south),
            )
            .mapNotNull { mapState.screenLocationFromPosition(it) }
            .takeIf { it.size == 4 }
        }
        DemoDestination.None -> null
      }
    }
  val color = Color(0xFFE53935)

  if (projected != null) {
    Canvas(modifier) {
      val points = projected.map { Offset(it.x.toPx(), it.y.toPx()) }
      if (points.size == 1) {
        drawCircle(
          color = color,
          radius = 12.dp.toPx(),
          center = points.single(),
          style = Stroke(width = 3.dp.toPx()),
        )
      } else {
        val path =
          Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
          }
        drawPath(path = path, color = color, style = Stroke(width = 3.dp.toPx()))
      }
    }
  }
}

@Composable
private fun PointerPinPlacementOverlay(
  mapState: MapState,
  target: Position,
  placementPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  val viewport = mapState.viewport
  val projected = remember(target, viewport) { mapState.screenLocationFromPosition(target) }
  val layoutDirection = LocalLayoutDirection.current

  Canvas(modifier) {
    val geometryColor = Color(0xFF00ACC1)
    val targetColor = Color(0xFFFFB300)
    val haloColor = Color.White.copy(alpha = 0.9f)
    val geometryWidth = 2.dp.toPx()
    val haloWidth = 5.dp.toPx()
    val area =
      Rect(
        left = placementPadding.calculateLeftPadding(layoutDirection).toPx(),
        top = placementPadding.calculateTopPadding().toPx(),
        right = size.width - placementPadding.calculateRightPadding(layoutDirection).toPx(),
        bottom = size.height - placementPadding.calculateBottomPadding().toPx(),
      )
    if (area.width <= 0 || area.height <= 0) return@Canvas

    drawOval(
      color = haloColor,
      topLeft = area.topLeft,
      size = area.size,
      style = Stroke(width = haloWidth),
    )
    drawOval(
      color = geometryColor,
      topLeft = area.topLeft,
      size = area.size,
      style = Stroke(width = geometryWidth),
    )

    val projectedTarget = projected?.let { Offset(it.x.toPx(), it.y.toPx()) } ?: return@Canvas
    val intersection = findEllipseIntersection(area, projectedTarget)

    if (intersection != null) {
      drawLine(
        color = haloColor,
        start = intersection,
        end = projectedTarget,
        strokeWidth = haloWidth,
      )
      drawLine(
        color = geometryColor,
        start = intersection,
        end = projectedTarget,
        strokeWidth = geometryWidth,
      )
      drawCircle(color = haloColor, radius = 6.dp.toPx(), center = intersection)
      drawCircle(color = geometryColor, radius = 4.dp.toPx(), center = intersection)
    }

    val targetRadius = 9.dp.toPx()
    val targetArm = 13.dp.toPx()
    drawCircle(
      color = haloColor,
      radius = targetRadius,
      center = projectedTarget,
      style = Stroke(width = haloWidth),
    )
    drawCircle(
      color = targetColor,
      radius = targetRadius,
      center = projectedTarget,
      style = Stroke(width = geometryWidth),
    )
    listOf(haloWidth to haloColor, geometryWidth to targetColor).forEach { (width, color) ->
      drawLine(
        color = color,
        start = Offset(projectedTarget.x - targetArm, projectedTarget.y),
        end = Offset(projectedTarget.x + targetArm, projectedTarget.y),
        strokeWidth = width,
      )
      drawLine(
        color = color,
        start = Offset(projectedTarget.x, projectedTarget.y - targetArm),
        end = Offset(projectedTarget.x, projectedTarget.y + targetArm),
        strokeWidth = width,
      )
    }
  }
}

// Matches the placement calculation in PointerPinButton.
private fun findEllipseIntersection(area: Rect, target: Offset): Offset? {
  val delta = target - area.center
  val horizontalRadius = area.width / 2.0
  val verticalRadius = area.height / 2.0
  val normalizedDistance =
    delta.x * delta.x / (horizontalRadius * horizontalRadius) +
      delta.y * delta.y / (verticalRadius * verticalRadius)
  if (normalizedDistance < 1.0) return null
  return area.center + delta * (1.0 / sqrt(normalizedDistance)).toFloat()
}

@Composable
private fun DiagnosticOverlays(state: DemoAppState, modifier: Modifier = Modifier) {
  Column(
    modifier =
      modifier
        .padding(8.dp)
        .background(
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
          shape = RoundedCornerShape(8.dp),
        )
        .padding(horizontal = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (state.settings.showPointerPinDiagnostics && state.selectedDemo?.pointerPin != null) {
      Text(
        text = "Destination: red · target: amber · placement: cyan",
        style = MaterialTheme.typography.labelMedium,
      )
    }
    if (state.settings.showFpsOverlay) {
      LaunchedEffect(state.frameRateState) { state.frameRateState.track() }
      Text(
        text = "${state.frameRateState.framesPerSecond.roundToInt()} fps",
        style = MaterialTheme.typography.labelMedium,
      )
    }
    if (state.settings.showCameraOverlay) {
      val position = state.mapState.cameraPosition
      Text(
        text =
          "lat ${position.target.latitude.format(4)} " +
            "lng ${position.target.longitude.format(4)} " +
            "zoom ${position.zoom.format(1)} " +
            "bearing ${position.bearing.format(0)} " +
            "tilt ${position.tilt.format(0)}",
        style = MaterialTheme.typography.labelMedium,
      )
    }
  }
}

private fun Double.format(decimals: Int): String {
  var factor = 1.0
  repeat(decimals) { factor *= 10 }
  val rounded = (this * factor).roundToInt() / factor
  return if (decimals == 0) rounded.roundToInt().toString() else rounded.toString()
}
