package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoMapControls
import org.maplibre.compose.demoapp.DemoPointerPin
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapSnapshotRequest
import org.maplibre.compose.demoapp.controlPadding
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.attributions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

private val SnapshotTarget = Position(longitude = -122.3358, latitude = 47.6086)
private val SnapshotMarkerColor = Color(0xFF00897B)
private const val MaxSnapshotCanvasPx = 4096f

object MapSnapshotterDemo : Demo {
  override val name = "Map snapshotter"
  override val description =
    "Frame a top-down shot with the on-map viewfinder, capture it, and share or save the photo."
  override val destination =
    DemoDestination.ExactCamera(CameraPosition(target = SnapshotTarget, zoom = 13.5))
  override val pointerPin = DemoPointerPin(SnapshotTarget, destination)

  private val topDownInteractions = MapInteractions { camera { tilt { enabled = false } } }

  override fun interactions(mapState: MapState): MapInteractions = topDownInteractions

  @Composable
  override fun MapContent(style: DemoStyle) {
    SnapshotMarker()
  }

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState, controls: DemoMapControls) {
    key(state.mapRuntime, state.appliedStyle.base) {
      MaterialTheme(motionScheme = MotionScheme.expressive()) {
        SnapshotStage(state, controls)
      }
    }
  }

  @Composable
  private fun SnapshotStage(app: DemoAppState, controls: DemoMapControls) {
    val mapState = app.mapState
    val state = remember { SnapshotterDemoState() }
    val baseStyle = app.appliedStyle.base
    val snapshotter = remember { app.mapRuntime.createSnapshotter(baseStyle) { SnapshotMarker() } }
    DisposableEffect(snapshotter) {
      // close() starts physical cleanup in the runtime and abandons any active capture.
      onDispose { snapshotter.close() }
    }
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var frame by remember { mutableStateOf<Rect?>(null) }
    var dock by remember { mutableStateOf<Rect?>(null) }

    fun bounds(child: LayoutCoordinates): Rect? =
      coordinates?.takeIf { it.isAttached }?.localBoundingBoxOf(child, clipBounds = false)

    fun capture() {
      val rect = frame?.takeIf { it.width > 0 && it.height > 0 } ?: return
      if (state.status is CaptureStatus.Capturing || mapState.cameraPosition.tilt != 0.0) return
      val center =
        mapState.positionFromScreenLocation(
          with(density) { DpOffset(rect.center.x.toDp(), rect.center.y.toDp()) }
        ) ?: return
      val width = (rect.width / density.density).roundToInt().coerceAtLeast(1)
      val height = (rect.height / density.density).roundToInt().coerceAtLeast(1)
      val request =
        MapSnapshotRequest(
          width,
          height,
          mapState.cameraPosition.copy(target = center),
          density =
            minOf(density.density, MaxSnapshotCanvasPx / width, MaxSnapshotCanvasPx / height),
          fontScale = density.fontScale,
          layoutDirection = direction,
        )
      state.status = CaptureStatus.Capturing
      state.flashTick++
      scope.launch {
        try {
          val image =
            snapshotter
              .capture(request)
              .withAttribution(
                snapshotter.style.attributions(),
                textMeasurer,
                Density(request.density, request.fontScale),
                direction,
              )
          state.captured = CapturedSnapshot(image, request, rect, (state.captured?.index ?: 0) + 1)
          state.status = CaptureStatus.Ready
        } catch (error: CancellationException) {
          throw error
        } catch (error: Exception) {
          state.status = CaptureStatus.Failed(error.message ?: "The snapshot capture failed")
        }
      }
    }

    Box(Modifier.fillMaxSize().onPlaced { coordinates = it }) {
      SnapshotScrim { frame }
      Box(Modifier.fillMaxSize().controlPadding()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
          val controlsMaxHeight = maxHeight / 2
          Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
              Column(Modifier.weight(1f)) {
                controls.scale()
                SnapshotFrame(
                  state.aspect,
                  Modifier.weight(1f),
                  onPositioned = { frame = bounds(it) },
                )
              }
              controls.buttons()
            }
            // Controls can scroll on short hosts; they never consume the entire viewfinder.
            SnapshotControls(
              state,
              canCapture =
                this@SnapshotStage.mapState.cameraPosition.tilt == 0.0 &&
                  frame?.let { it.width > 0 && it.height > 0 } == true,
              modifier =
                Modifier.heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState()),
              onCapture = ::capture,
              onDockPositioned = { dock = bounds(it) },
            )
            controls.attribution()
          }
        }
      }
      SnapshotFlash(state.flashTick)
      state.captured?.let { shot ->
        key(shot.index) {
          SnapshotFlight(
            shot,
            dock,
            onOpen = { state.sheetOpen = true },
            modifier = Modifier.align(AbsoluteAlignment.TopLeft),
          )
        }
      }
    }
    SnapshotSheet(state)
  }

  @Composable
  private fun SnapshotMarker() {
    val source =
      rememberGeoJsonSource(
        GeoJsonData.Features(Feature(geometry = Point(SnapshotTarget), properties = null))
      )
    CircleLayer(
      id = "snapshot-demo-marker",
      source = source,
      radius = const(9.dp),
      color = const(SnapshotMarkerColor),
      strokeWidth = const(3.dp),
      strokeColor = const(Color.White),
    )
  }
}
