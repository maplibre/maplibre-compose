@file:OptIn(ExperimentalTime::class)

package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.demoapp.DefaultMapControls
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoMapControls
import org.maplibre.compose.demoapp.DemoPointerPin
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.demoapp.Protomaps
import org.maplibre.compose.demoapp.center
import org.maplibre.compose.demoapp.demos.FerrySchedule.Network
import org.maplibre.compose.demoapp.demos.FerrySchedule.RouteDepartures
import org.maplibre.compose.demoapp.demos.FerrySchedule.loadNetwork
import org.maplibre.compose.demoapp.demos.FerrySchedule.routeDepartures
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.spatialk.geojson.BoundingBox

object TransitNetworkDemo : Demo {
  override val name = "Transit network"
  override val description =
    "The Washington State Ferries network from its GTFS feed. Select a route to see the next sailing at each terminal."
  private val networkRegion = BoundingBox(west = -123.2, south = 47.0, east = -122.2, north = 48.8)
  override val destination = DemoDestination.FitBounds(networkRegion)
  override val pointerPin = DemoPointerPin(networkRegion.center, destination)
  override val preferredLightStyle = Protomaps.Light
  override val preferredDarkStyle = Protomaps.Dark

  private val RouteFitPadding = PaddingValues(horizontal = 96.dp, vertical = 72.dp)

  private sealed interface FeedState {
    data object Loading : FeedState

    data class Failed(val message: String) : FeedState

    data class Loaded(val network: Network) : FeedState
  }

  private var feedState by mutableStateOf<FeedState>(FeedState.Loading)
  private var selectedRouteId by mutableStateOf<String?>(null)
  private var selectedDepartures by mutableStateOf<RouteDepartures?>(null)

  @Composable
  private fun LoadFeed() {
    LaunchedEffect(Unit) {
      if (feedState is FeedState.Loaded) return@LaunchedEffect
      feedState =
        try {
          FeedState.Loaded(loadNetwork())
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          FeedState.Failed("Couldn't load the ferry feed: ${e.message}")
        }
    }
  }

  @Composable
  override fun MapContent(style: DemoStyle) {
    val mapState = checkNotNull(LocalMapState.current)
    val network = (feedState as? FeedState.Loaded)?.network ?: return
    val selected = selectedRouteId

    LaunchedEffect(selected, mapState) {
      val route = network.routes.find { it.id == selected } ?: return@LaunchedEffect
      mapState.animateCameraToBounds(
        boundingBox = route.bounds,
        padding = RouteFitPadding,
        animation = CameraAnimation.Fly(1.seconds),
      )
    }

    FerryMapContent(network, selected, style)
  }

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState, controls: DemoMapControls) {
    DemoOverlay()
    DefaultMapControls(controls)
  }

  @Composable
  private fun MapOverlayScope.DemoOverlay() {
    LoadFeed()
    val network = (feedState as? FeedState.Loaded)?.network ?: return
    val selected = selectedRouteId
    LaunchedEffect(network, selected) {
      if (selected == null) {
        selectedDepartures = null
        return@LaunchedEffect
      }
      while (true) {
        selectedDepartures = withContext(Dispatchers.Default) { routeDepartures(network, selected) }
        delay(30.seconds)
      }
    }
    val routeId = selected ?: return
    val departures = selectedDepartures?.takeIf { it.routeId == routeId } ?: return

    network.stopIdsByRoute[routeId].orEmpty().forEach { stopId ->
      key(stopId) {
        val terminal = network.terminalsById[stopId]
        val departure = departures.nextByStopId[stopId]
        if (terminal != null && departure != null) {
          DepartureChip(
            text = departure,
            modifier =
              Modifier.placedAt(terminal.position, alignment = Alignment.BottomCenter)
                .padding(bottom = 8.dp),
          )
        }
      }
    }
  }

  @Composable
  override fun Panel(state: DemoAppState) {
    when (val state = feedState) {
      is FeedState.Loading ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.padding(16.dp),
        ) {
          CircularProgressIndicator(Modifier.size(24.dp))
          Text("Loading the ferry feed")
        }

      is FeedState.Failed ->
        Text(
          text = state.message,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(16.dp),
        )

      is FeedState.Loaded -> {
        SectionHeader("Routes")
        state.network.routes.forEach { route ->
          val isSelected = route.id == selectedRouteId
          val departures = selectedDepartures?.takeIf { it.routeId == route.id }
          ListItem(
            headlineContent = { Text(route.displayName) },
            leadingContent = { Box(Modifier.size(12.dp).background(route.color, CircleShape)) },
            supportingContent =
              if (isSelected && departures != null) {
                {
                  Text(
                    if (departures.nextSailings.isEmpty()) "No sailings in the next day"
                    else "Next sailings: ${departures.nextSailings.joinToString(", ")}"
                  )
                }
              } else null,
            colors =
              ListItemDefaults.colors(
                containerColor =
                  if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                  else Color.Transparent
              ),
            modifier = Modifier.clickable { selectedRouteId = if (isSelected) null else route.id },
          )
        }
      }
    }
  }
}

@Composable
private fun DepartureChip(text: String, modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    shadowElevation = 2.dp,
    color = MaterialTheme.colorScheme.surface,
    modifier = modifier,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}
