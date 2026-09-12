package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoPointerPin
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapSnapshotRequest
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

private val SnapshotTarget = Position(longitude = -122.3358, latitude = 47.6086)
private val SnapshotMarkerColor = Color(0xFF00897B)

/** A camera viewfinder for [org.maplibre.compose.map.MapSnapshotter]: frame, shoot, share. */
object MapSnapshotterDemo : Demo {
  override val name = "Map snapshotter"
  override val description =
    "Frame a shot with the on-map viewfinder, capture it, and share or save the photo."
  override val destination =
    DemoDestination.ExactCamera(CameraPosition(target = SnapshotTarget, zoom = 13.5))
  override val pointerPin = DemoPointerPin(SnapshotTarget, destination)

  private val demoState = SnapshotterDemoState()
  private var activeSession: Any? = null

  private data class CaptureJob(val request: MapSnapshotRequest, val frame: Rect)

  @Composable
  override fun MapContent(style: DemoStyle) {
    SnapshotMarker()
  }

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState) {
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
      val appliedBaseStyle = state.appliedStyle.base
      val snapshotter =
        remember(state.mapRuntime, appliedBaseStyle) {
          state.mapRuntime.createSnapshotter(
            baseStyle = appliedBaseStyle,
            content = { SnapshotMarker() },
          )
        }
      val captureRequests = remember(snapshotter) { Channel<CaptureJob>(capacity = 1) }
      val session = remember(snapshotter) { Any() }
      val density = LocalDensity.current
      val layoutDirection = LocalLayoutDirection.current

      val beginCapture: () -> Unit =
        remember(mapState, density, layoutDirection, captureRequests) {
          capture@{
            val frame = demoState.frameBounds ?: return@capture
            if (demoState.status is CaptureStatus.Capturing) return@capture
            val request = buildRequest(mapState, frame, density, layoutDirection) ?: return@capture
            if (captureRequests.trySend(CaptureJob(request, frame)).isSuccess) {
              demoState.flashTick++
              demoState.status = CaptureStatus.Capturing
              demoState.cleanupFailure = null
              demoState.actionMessage = null
            }
          }
        }

      DisposableEffect(snapshotter) {
        activeSession = session
        onDispose {
          if (activeSession === session) {
            activeSession = null
            // Reset per-session state on the way out so a later visit never replays a stale
            // flash, flight, or failure before this effect's body would run. frameBounds is
            // deliberately kept: SnapshotFrame stays composed across a snapshotter replacement,
            // and its mirror flow only republishes when the rect changes.
            demoState.status = CaptureStatus.Ready
            demoState.captured = null
            demoState.cleanupFailure = null
            demoState.sheetOpen = false
            demoState.flashTick = 0
            demoState.runningAction = null
            demoState.actionMessage = null
            demoState.actionFailed = false
          }
          captureRequests.close()
          // Close synchronously so an active capture is abandoned as soon as the demo leaves.
          snapshotter.close()
        }
      }

      LaunchedEffect(snapshotter) {
        try {
          for (job in captureRequests) {
            try {
              val image = snapshotter.capture(job.request)
              if (activeSession === session) {
                demoState.captureCount++
                demoState.captured =
                  CapturedSnapshot(
                    image,
                    job.request,
                    frame = job.frame,
                    index = demoState.captureCount,
                  )
                demoState.status = CaptureStatus.Ready
              }
            } catch (error: CancellationException) {
              throw error
            } catch (error: Throwable) {
              if (activeSession === session) {
                demoState.status =
                  CaptureStatus.Failed(error.message ?: "The snapshot capture failed")
              }
            }
          }
        } finally {
          // The snapshotter can own a GPU target. Wait for physical cleanup even after
          // cancellation.
          withContext(NonCancellable) {
            snapshotter.close()
            try {
              snapshotter.awaitClosed()
            } catch (error: Throwable) {
              if (activeSession === session) {
                demoState.cleanupFailure = error.message ?: "unknown error"
              }
            }
          }
        }
      }

      SnapshotterStage(beginCapture)
    }
    SnapshotSheet(demoState)
  }

  /**
   * The viewfinder stage over the map. The content insets of the overlay host place this child over
   * the unobstructed map region, and [onGloballyPositioned] reports where that region sits in map
   * coordinates, which the capture request needs. The scrim and flash read the whole map's size
   * from the viewport so they can dim past the safe area to the map's edges.
   */
  @Composable
  private fun MapOverlayScope.SnapshotterStage(beginCapture: () -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    // The viewport changes with every camera frame; only its size matters here.
    val fullMap by remember { derivedStateOf { mapState.viewport?.size } }
    var controlsHeight by remember { mutableStateOf(0.dp) }
    BoxWithConstraints(
      Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInParent() }
    ) {
      val safe = DpSize(maxWidth, maxHeight)
      val originDp = with(density) { DpOffset(origin.x.toDp(), origin.y.toDp()) }
      SnapshotFrame(demoState, safe, originDp, fullMap, bottomClearance = controlsHeight)
      SnapshotFlash(demoState.flashTick, originDp, fullMap)
      SnapshotFlight(demoState, safe, originDp, onOpen = { demoState.sheetOpen = true })
      SnapshotControls(demoState, onCapture = beginCapture, onHeight = { controlsHeight = it })
    }
  }

  private fun buildRequest(
    mapState: MapState,
    frame: Rect,
    density: Density,
    layoutDirection: LayoutDirection,
  ): MapSnapshotRequest? {
    val center =
      mapState.positionFromScreenLocation(DpOffset(frame.center.x.dp, frame.center.y.dp))
        ?: return null
    return MapSnapshotRequest(
      width = frame.width.roundToInt().coerceAtLeast(1),
      height = frame.height.roundToInt().coerceAtLeast(1),
      cameraPosition = mapState.cameraPosition.copy(target = center),
      density = density.density,
      fontScale = density.fontScale,
      layoutDirection = layoutDirection,
    )
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
