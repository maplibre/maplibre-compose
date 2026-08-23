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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.sargunv.mobilitydata.gtfs.schedule.Agency
import dev.sargunv.mobilitydata.gtfs.schedule.GtfsCsv
import dev.sargunv.mobilitydata.gtfs.schedule.PickupDropoff
import dev.sargunv.mobilitydata.gtfs.schedule.Route
import dev.sargunv.mobilitydata.gtfs.schedule.ServiceCalendar
import dev.sargunv.mobilitydata.gtfs.schedule.Shape
import dev.sargunv.mobilitydata.gtfs.schedule.Stop
import dev.sargunv.mobilitydata.gtfs.schedule.StopTime
import dev.sargunv.mobilitydata.gtfs.schedule.Trip
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.OpenFreeMap
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.util.unzip
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

object TransitNetworkDemo : Demo {
  override val name = "Transit network"
  override val description =
    "The Washington State Ferries network from its GTFS feed. Select a route to see the next sailing at each terminal."
  override val region = BoundingBox(west = -123.2, south = 47.0, east = -122.2, north = 48.8)
  override val preferredStyle = OpenFreeMap.Positron

  /** The feed sends no CORS headers, which is why this demo is absent from the browser. */
  private const val FEED_URI =
    "https://business.wsdot.wa.gov/Transit/csv_files/wsf/google_transit.zip"

  /** Camera padding that leaves room for a departure chip above each terminal. */
  private val RouteFitPadding = PaddingValues(horizontal = 96.dp, vertical = 72.dp)

  // WSF's routes.txt assigns no colors, so the demo assigns its own.
  private val palette =
    listOf(
      "#1976D2",
      "#388E3C",
      "#E64A19",
      "#7B1FA2",
      "#0097A7",
      "#F57C00",
      "#C2185B",
      "#5D4037",
      "#455A64",
      "#AFB42B",
    )

  private class RouteEntry(
    val id: String,
    val displayName: String,
    val color: Color,
    val bounds: BoundingBox,
  )

  private class Terminal(val id: String, val name: String, val position: Position)

  private class Network(
    val routes: List<RouteEntry>,
    val routeLines: FeatureCollection<LineString, JsonObject>,
    val terminals: FeatureCollection<Point, JsonObject>,
    val terminalsById: Map<String, Terminal>,
    val stopIdsByRoute: Map<String, Set<String>>,
    val timeZone: TimeZone,
    val tripsByRoute: Map<String, List<Trip>>,
    val stopTimesByTrip: Map<String, List<StopTime>>,
    val firstStopTimeByTrip: Map<String, StopTime>,
    val calendars: List<ServiceCalendar>,
  )

  private sealed interface FeedState {
    data object Loading : FeedState

    data class Failed(val message: String) : FeedState

    data class Loaded(val network: Network) : FeedState
  }

  private var feedState by mutableStateOf<FeedState>(FeedState.Loading)
  private var selectedRouteId by mutableStateOf<String?>(null)

  private suspend fun loadNetwork(): Network =
    withContext(Dispatchers.Default) {
      val zipBytes = HttpClient().use { client -> client.get(FEED_URI).bodyAsBytes() }
      val files = unzip(zipBytes)
      fun table(name: String) = files.getValue(name).decodeToString()

      val agencies = GtfsCsv.decodeFromString<Agency>(table("agency.txt"))
      val routes = GtfsCsv.decodeFromString<Route>(table("routes.txt"))
      val stops = GtfsCsv.decodeFromString<Stop>(table("stops.txt"))
      val shapes = GtfsCsv.decodeFromString<Shape>(table("shapes.txt"))
      val trips = GtfsCsv.decodeFromString<Trip>(table("trips.txt"))
      val stopTimes = GtfsCsv.decodeFromString<StopTime>(table("stop_times.txt"))
      val calendars = GtfsCsv.decodeFromString<ServiceCalendar>(table("calendar.txt"))

      val pointsByShape =
        shapes
          .groupBy { it.shapeId }
          .mapValues { (_, points) ->
            points
              .sortedBy { it.shapePointSequence }
              .map {
                Position(longitude = it.shapePointLongitude, latitude = it.shapePointLatitude)
              }
          }
      val tripsByRoute = trips.groupBy { it.routeId }
      val stopTimesByTrip = stopTimes.groupBy { it.tripId }

      val terminalList = stops.mapNotNull { stop ->
        val longitude = stop.stopLongitude ?: return@mapNotNull null
        val latitude = stop.stopLatitude ?: return@mapNotNull null
        Terminal(
          id = stop.stopId,
          name = stop.stopName ?: stop.stopId,
          position = Position(longitude = longitude, latitude = latitude),
        )
      }
      val terminalsById = terminalList.associateBy { it.id }
      val stopIdsByRoute = tripsByRoute.mapValues { (_, routeTrips) ->
        routeTrips
          .flatMap { trip -> stopTimesByTrip[trip.tripId].orEmpty() }
          .filter { it.allowsBoarding }
          .mapTo(mutableSetOf()) { it.stopId }
      }

      val lineFeatures = mutableListOf<Feature<LineString, JsonObject>>()
      val routeEntries = mutableListOf<RouteEntry>()
      routes.forEachIndexed { index, route ->
        val positions =
          tripsByRoute[route.routeId]
            .orEmpty()
            .mapNotNull { it.shapeId }
            .distinct()
            .mapNotNull { pointsByShape[it] }
        if (positions.isEmpty()) return@forEachIndexed
        val colorHex = palette[index % palette.size]
        positions.forEach { line ->
          lineFeatures +=
            Feature(
              geometry = LineString(line),
              properties =
                buildJsonObject {
                  put("route", route.routeId)
                  put("color", colorHex)
                },
            )
        }
        val all =
          positions.flatten() +
            stopIdsByRoute[route.routeId].orEmpty().mapNotNull { id -> terminalsById[id]?.position }
        routeEntries +=
          RouteEntry(
            id = route.routeId,
            displayName = route.routeLongName ?: route.routeShortName ?: route.routeId,
            color = Color(0xFF000000 or colorHex.drop(1).toLong(16)),
            bounds =
              BoundingBox(
                west = all.minOf { it.longitude },
                south = all.minOf { it.latitude },
                east = all.maxOf { it.longitude },
                north = all.maxOf { it.latitude },
              ),
          )
      }

      val terminalFeatures = terminalList.map { terminal ->
        Feature(
          geometry = Point(terminal.position),
          properties = buildJsonObject { put("name", terminal.name) },
        )
      }

      Network(
        routes = routeEntries.sortedBy { it.displayName },
        routeLines = FeatureCollection(lineFeatures),
        terminals = FeatureCollection(terminalFeatures),
        terminalsById = terminalsById,
        stopIdsByRoute = stopIdsByRoute,
        timeZone = agencies.first().agencyTimezone,
        tripsByRoute = tripsByRoute,
        stopTimesByTrip = stopTimesByTrip,
        firstStopTimeByTrip =
          stopTimesByTrip.mapValues { (_, times) -> times.minBy { it.stopSequence } },
        calendars = calendars,
      )
    }

  private val StopTime.allowsBoarding: Boolean
    get() = departureTime != null && pickupType != PickupDropoff.None

  private fun ServiceCalendar.runsOn(day: DayOfWeek): Boolean =
    when (day) {
      DayOfWeek.MONDAY -> monday
      DayOfWeek.TUESDAY -> tuesday
      DayOfWeek.WEDNESDAY -> wednesday
      DayOfWeek.THURSDAY -> thursday
      DayOfWeek.FRIDAY -> friday
      DayOfWeek.SATURDAY -> saturday
      DayOfWeek.SUNDAY -> sunday
    }

  private fun activeServiceIds(network: Network, date: LocalDate): Set<String> =
    network.calendars
      .filter { date in it.startDate..it.endDate && it.runsOn(date.dayOfWeek) }
      .mapTo(mutableSetOf()) { it.serviceId }

  private fun formatSailing(instant: Instant, headsign: String, timeZone: TimeZone): String {
    val time = instant.toLocalDateTime(timeZone).time
    val hhmm = "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    return if (headsign.isEmpty()) hhmm else "$hhmm $headsign"
  }

  /** The next few departures from each route's first terminal, over today and tomorrow. */
  private fun nextDepartures(
    network: Network,
    routeId: String,
    now: Instant = Clock.System.now(),
    count: Int = 3,
  ): List<String> {
    val today = now.toLocalDateTime(network.timeZone).date
    return listOf(today, today.plus(1, DateTimeUnit.DAY))
      .flatMap { date ->
        val services = activeServiceIds(network, date)
        network.tripsByRoute[routeId]
          .orEmpty()
          .filter { it.serviceId in services }
          .mapNotNull { trip ->
            val departure = network.firstStopTimeByTrip[trip.tripId]?.departureTime
            departure?.toInstant(date, network.timeZone)?.let { instant ->
              instant to (trip.tripHeadsign ?: "")
            }
          }
      }
      .filter { (instant, _) -> instant >= now }
      .sortedBy { (instant, _) -> instant }
      .take(count)
      .map { (instant, headsign) -> formatSailing(instant, headsign, network.timeZone) }
  }

  /** The next departure from [stopId] on [routeId], or null when none remains today or tomorrow. */
  private fun nextDepartureFromStop(
    network: Network,
    routeId: String,
    stopId: String,
    now: Instant,
  ): String? {
    val today = now.toLocalDateTime(network.timeZone).date
    return listOf(today, today.plus(1, DateTimeUnit.DAY))
      .flatMap { date ->
        val services = activeServiceIds(network, date)
        network.tripsByRoute[routeId]
          .orEmpty()
          .filter { it.serviceId in services }
          .mapNotNull { trip ->
            val stopTime =
              network.stopTimesByTrip[trip.tripId]?.find {
                it.stopId == stopId && it.allowsBoarding
              }
            val departure = stopTime?.departureTime ?: return@mapNotNull null
            departure.toInstant(date, network.timeZone) to
              (stopTime.stopHeadsign ?: trip.tripHeadsign ?: "")
          }
      }
      .filter { (instant, _) -> instant >= now }
      .minByOrNull { (instant, _) -> instant }
      ?.let { (instant, headsign) -> formatSailing(instant, headsign, network.timeZone) }
  }

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
  override fun MapContent(cameraState: CameraState) {
    val network = (feedState as? FeedState.Loaded)?.network ?: return
    val selected = selectedRouteId

    LaunchedEffect(selected) {
      val route = network.routes.find { it.id == selected } ?: return@LaunchedEffect
      cameraState.animateTo(
        boundingBox = route.bounds,
        padding = RouteFitPadding,
        duration = 1.seconds,
      )
    }

    val routeSource = rememberGeoJsonSource(GeoJsonData.Features(network.routeLines))
    val terminalSource = rememberGeoJsonSource(GeoJsonData.Features(network.terminals))

    LineLayer(
      id = "transit-routes",
      source = routeSource,
      color = feature["color"].asString().convertToColor(),
      width = const(3.dp),
      opacity = if (selected == null) const(0.8f) else const(0.2f),
    )
    if (selected != null) {
      LineLayer(
        id = "transit-route-selected",
        source = routeSource,
        filter = feature["route"].asString() eq const(selected),
        color = feature["color"].asString().convertToColor(),
        width = const(4.dp),
      )
    }

    CircleLayer(
      id = "transit-terminals",
      source = terminalSource,
      radius = const(4.dp),
      color = const(Color.White),
      strokeWidth = const(2.dp),
      strokeColor = const(Color(0xFF37474F)),
    )
    SymbolLayer(
      id = "transit-terminal-names",
      source = terminalSource,
      textField = feature["name"].asString(),
      textFont = const(preferredStyle.textFont),
      textColor = const(Color(0xFF37474F)),
      textHaloColor = const(Color.White),
      textHaloWidth = const(1.dp),
      textAnchor = const(SymbolAnchor.Top),
      textOffset = offset(0.em, 0.4.em),
    )
  }

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState) {
    LoadFeed()
    val network = (feedState as? FeedState.Loaded)?.network ?: return
    val selected = selectedRouteId ?: return
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) {
      while (true) {
        delay(30.seconds)
        now = Clock.System.now()
      }
    }

    network.stopIdsByRoute[selected].orEmpty().forEach { stopId ->
      key(stopId) {
        val terminal = network.terminalsById[stopId]
        val departure =
          remember(selected, now) {
            terminal?.let { nextDepartureFromStop(network, selected, stopId, now) }
          }
        if (terminal != null && departure != null) {
          DepartureChip(
            text = departure,
            modifier =
              Modifier.placedAt(terminal.position, Alignment.BottomCenter).padding(bottom = 8.dp),
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
          ListItem(
            headlineContent = { Text(route.displayName) },
            leadingContent = { Box(Modifier.size(12.dp).background(route.color, CircleShape)) },
            supportingContent =
              if (isSelected) {
                {
                  val departures = remember(route.id) { nextDepartures(state.network, route.id) }
                  Text(
                    if (departures.isEmpty()) "No sailings in the next day"
                    else "Next sailings: ${departures.joinToString(", ")}"
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
