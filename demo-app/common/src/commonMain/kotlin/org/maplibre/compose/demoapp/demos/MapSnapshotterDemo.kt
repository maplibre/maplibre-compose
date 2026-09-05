package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoPointerPin
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapSnapshotRequest
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

private val SnapshotTarget = Position(longitude = -122.3358, latitude = 47.6086)
private val SnapshotMarkerColor = Color(0xFF00897B)

/** Captures the current map camera into an image through an independent non-UI map. */
object MapSnapshotterDemo : Demo {
  override val name = "Map snapshotter"
  override val description = "Capture the current camera and style with an independent non-UI map."
  override val destination =
    DemoDestination.ExactCamera(CameraPosition(target = SnapshotTarget, zoom = 13.5))
  override val pointerPin = DemoPointerPin(SnapshotTarget, destination)

  private enum class SnapshotSize(val label: String, val width: Int, val height: Int) {
    Landscape("Landscape", 320, 180),
    Square("Square", 240, 240),
    Portrait("Portrait", 180, 320),
  }

  private sealed interface CaptureStatus {
    data object Ready : CaptureStatus

    data object Capturing : CaptureStatus

    data class Failed(val message: String) : CaptureStatus
  }

  private data class CapturedSnapshot(
    val image: ImageBitmap,
    val request: MapSnapshotRequest,
  )

  private var snapshotSize by mutableStateOf(SnapshotSize.Landscape)
  private var status by mutableStateOf<CaptureStatus>(CaptureStatus.Ready)
  private var capturedSnapshot by mutableStateOf<CapturedSnapshot?>(null)
  private var cleanupFailure by mutableStateOf<String?>(null)
  private var captureCurrentCamera by mutableStateOf<(() -> Unit)?>(null)
  private var activeSession: Any? = null

  @Composable
  override fun MapContent() {
    SnapshotMarker()
  }

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState) {
    val appliedBaseStyle = state.appliedStyle.base
    val snapshotter =
      remember(state.mapRuntime, appliedBaseStyle) {
        state.mapRuntime.createSnapshotter(
          baseStyle = appliedBaseStyle,
          content = { SnapshotMarker() },
        )
      }
    val captureRequests = remember(snapshotter) { Channel<Unit>(capacity = 1) }
    val session = remember(snapshotter) { Any() }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val currentRequest by
      rememberUpdatedState(
        MapSnapshotRequest(
          width = snapshotSize.width,
          height = snapshotSize.height,
          cameraPosition = state.mapState.cameraPosition,
          density = density.density,
          fontScale = density.fontScale,
          layoutDirection = layoutDirection,
        )
      )
    val requestCapture =
      remember(captureRequests) {
        {
          if (captureRequests.trySend(Unit).isSuccess) {
            cleanupFailure = null
            status = CaptureStatus.Capturing
          }
        }
      }

    DisposableEffect(snapshotter, requestCapture) {
      activeSession = session
      captureCurrentCamera = requestCapture
      status = CaptureStatus.Ready
      capturedSnapshot = null
      cleanupFailure = null
      onDispose {
        if (activeSession === session) {
          activeSession = null
          captureCurrentCamera = null
        }
        captureRequests.close()
        // Close synchronously so an active capture is abandoned as soon as the demo leaves.
        snapshotter.close()
      }
    }

    LaunchedEffect(snapshotter) {
      try {
        for (ignored in captureRequests) {
          val request = currentRequest
          try {
            val image = snapshotter.capture(request)
            if (activeSession === session) {
              capturedSnapshot = CapturedSnapshot(image, request)
              status = CaptureStatus.Ready
            }
          } catch (error: CancellationException) {
            throw error
          } catch (error: Throwable) {
            if (activeSession === session) {
              status = CaptureStatus.Failed(error.message ?: "The snapshot capture failed")
            }
          }
        }
      } finally {
        // The snapshotter can own a GPU target. Wait for physical cleanup even after cancellation.
        withContext(NonCancellable) {
          snapshotter.close()
          try {
            snapshotter.awaitClosed()
          } catch (error: Throwable) {
            if (activeSession === session) {
              cleanupFailure = error.message ?: "The snapshotter cleanup failed"
            }
          }
        }
      }
    }

    capturedSnapshot?.let { snapshot ->
      Box(Modifier.fillMaxSize().windowInsetsPadding(contentWindowInsets)) {
        Surface(
          modifier =
            Modifier.align(Alignment.BottomEnd)
              .padding(24.dp)
              .fillMaxWidth(0.65f)
              .widthIn(max = 480.dp)
              .aspectRatio(snapshot.request.width.toFloat() / snapshot.request.height),
          shape = MaterialTheme.shapes.large,
          shadowElevation = 12.dp,
        ) {
          Image(
            bitmap = snapshot.image,
            contentDescription = "Latest map snapshot",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }
  }

  @Composable
  override fun Panel(state: DemoAppState) {
    SectionHeader("Capture")
    SegmentedRow(
      label = "Viewport",
      options = SnapshotSize.entries,
      selected = snapshotSize,
      optionLabel = SnapshotSize::label,
      onSelect = { snapshotSize = it },
    )
    Button(
      onClick = { captureCurrentCamera?.invoke() },
      enabled = captureCurrentCamera != null && status !is CaptureStatus.Capturing,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Text("Capture current camera")
    }

    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      val currentCleanupFailure = cleanupFailure
      val currentStatus = status
      when {
        currentCleanupFailure != null ->
          Text(
            "Snapshot cleanup failed: $currentCleanupFailure",
            color = MaterialTheme.colorScheme.error,
          )
        currentStatus is CaptureStatus.Capturing ->
          Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text("Capturing snapshot")
          }
        currentStatus is CaptureStatus.Failed ->
          Text(currentStatus.message, color = MaterialTheme.colorScheme.error)
        capturedSnapshot == null ->
          Text(
            "The teal marker appears in both the live map and the captured image.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        else -> {
          val snapshot = checkNotNull(capturedSnapshot)
          Text(
            "${snapshot.image.width} × ${snapshot.image.height} px from " +
              "${snapshot.request.width} × ${snapshot.request.height} at " +
              "${snapshot.request.density.toDouble().format(1)}× density · " +
              "zoom ${snapshot.request.cameraPosition.zoom.format(1)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
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

private fun Double.format(decimals: Int): String {
  var factor = 1.0
  repeat(decimals) { factor *= 10 }
  val rounded = kotlin.math.round(this * factor) / factor
  return rounded.toString()
}
