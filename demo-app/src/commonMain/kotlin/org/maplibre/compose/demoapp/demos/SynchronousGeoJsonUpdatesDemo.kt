package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.design.SwitchListItem
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

object SynchronousGeoJsonUpdatesDemo : Demo {
  override val name = "Synchronous GeoJSON updates"

  override val region =
    BoundingBox(
      southwest = Position(longitude = -122.353, latitude = 47.600),
      northeast = Position(longitude = -122.317, latitude = 47.622),
    )

  override val mapContentVisibilityState = mutableStateOf(false)

  private var synchronousUpdateEnabled by mutableStateOf(true)
  private var followCameraEnabled by mutableStateOf(true)
  private var zoomLevel by mutableStateOf(18)

  @Composable
  override fun MapContent(state: DemoState, isOpen: Boolean) {
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
      while (true) {
        delay(50)
        tick += 1
      }
    }

    val position = animatedPosition(tick)
    if (isOpen && followCameraEnabled) {
      LaunchedEffect(position, synchronousUpdateEnabled) {
        state.cameraState.position =
          CameraPosition(
            target = position,
            zoom = zoomLevel.toDouble(),
            tilt = 50.0,
            bearing = animatedBearing(tick),
          )
      }
    }

    val source =
      rememberGeoJsonSource(
        data =
          GeoJsonData.Features(
            FeatureCollection(listOf(Feature(geometry = Point(position), properties = null)))
          ),
        options = GeoJsonOptions(synchronousUpdate = synchronousUpdateEnabled),
      )

    CircleLayer(
      id = "sync-geojson-updates-shadow",
      source = source,
      radius = const(16.dp),
      color = const(Color.Black),
      blur = const(1.2f),
      opacity = const(0.25f),
      translate = offset(0.dp, 2.dp),
    )

    CircleLayer(
      id = "sync-geojson-updates-marker",
      source = source,
      radius = const(10.dp),
      color = const(if (synchronousUpdateEnabled) Color.Blue else Color.Red),
      strokeWidth = const(3.dp),
      strokeColor = const(Color.White),
    )
  }

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    SynchronousGeoJsonUpdatesDemoSheet(
      modifier = modifier.fillMaxWidth(),
      synchronousUpdateEnabled = synchronousUpdateEnabled,
      followCameraEnabled = followCameraEnabled,
      zoomLevel = zoomLevel,
      onSynchronousUpdateChange = { synchronousUpdateEnabled = it },
      onFollowCameraChange = { followCameraEnabled = it },
    )
  }

  @Composable
  override fun MapOverlayContent(state: DemoState, isOpen: Boolean) {
    SynchronousGeoJsonUpdatesMapOverlay(
      onZoomIn = { state.updateZoom((zoomLevel + 1).coerceAtMost(22)) },
      onZoomOut = { state.updateZoom((zoomLevel - 1).coerceAtLeast(3)) },
      modifier = Modifier.fillMaxSize().padding(12.dp),
    )
  }

  private fun DemoState.updateZoom(newZoom: Int) {
    zoomLevel = newZoom
    cameraState.position = cameraState.position.copy(zoom = newZoom.toDouble())
  }

  private fun animatedPosition(tick: Int): Position {
    val phase = (tick % LOOP_STEPS).toDouble() / LOOP_STEPS
    val angle = phase * 2.0 * PI
    return Position(
      longitude = CENTER.longitude + cos(angle) * LONGITUDE_RADIUS,
      latitude = CENTER.latitude + sin(angle) * LATITUDE_RADIUS,
    )
  }

  private fun animatedBearing(tick: Int): Double {
    val phase = (tick % LOOP_STEPS).toDouble() / LOOP_STEPS
    return (phase * 360.0 + 90.0) % 360.0
  }

  private val CENTER = Position(longitude = -122.3358, latitude = 47.6101)
  private const val LONGITUDE_RADIUS = 0.0045
  private const val LATITUDE_RADIUS = 0.0018
  private const val LOOP_STEPS = 120
}

@Composable
fun SynchronousGeoJsonUpdatesDemoSheet(
  synchronousUpdateEnabled: Boolean,
  followCameraEnabled: Boolean,
  zoomLevel: Int,
  onSynchronousUpdateChange: (Boolean) -> Unit,
  onFollowCameraChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  CardColumn(
    modifier = modifier.fillMaxWidth(),
    contentPadding = PaddingValues(vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    SwitchListItem(
      text = "Use synchronousUpdate",
      checked = synchronousUpdateEnabled,
      onCheckedChange = onSynchronousUpdateChange,
    )
    SwitchListItem(
      text = "Follow point with camera",
      checked = followCameraEnabled,
      onCheckedChange = onFollowCameraChange,
    )
    Text(text = "Zoom: $zoomLevel", modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    Text(
      text = "Android only. The point updates every 50 ms.",
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    Text(
      text = "Use the map controls to compare sync on/off and tighter vs. wider follow.",
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
  }
}

@Composable
fun SynchronousGeoJsonUpdatesMapOverlay(
  onZoomIn: () -> Unit,
  onZoomOut: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier) {
    Card(modifier = Modifier.align(Alignment.CenterEnd)) {
      Column {
        ZoomButton(label = "+", onClick = onZoomIn)
        ZoomButton(label = "−", onClick = onZoomOut)
      }
    }
  }
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
  TextButton(onClick = onClick, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(8.dp)) {
    Text(text = label, style = MaterialTheme.typography.headlineMedium)
  }
}
