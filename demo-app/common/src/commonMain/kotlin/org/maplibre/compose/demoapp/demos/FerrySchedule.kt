@file:OptIn(ExperimentalTime::class)

package org.maplibre.compose.demoapp.demos

import androidx.compose.ui.graphics.Color
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
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
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
import org.maplibre.compose.demoapp.util.unzip
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

internal object FerrySchedule {
  /** Mobility Database refreshes this browser-accessible mirror from WSDOT each day. */
  private const val FEED_URI = "https://files.mobilitydatabase.org/mdb-283/latest.zip"

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

  class RouteEntry(
    val id: String,
    val displayName: String,
    val color: Color,
    val bounds: BoundingBox,
  )

  class Terminal(val id: String, val name: String, val position: Position)

  class Network(
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

  data class Sailing(val instant: Instant, val headsign: String)

  data class RouteDepartures(
    val routeId: String,
    val nextSailings: List<String>,
    val nextByStopId: Map<String, String>,
    val sailingsByStopId: Map<String, List<Sailing>>,
  )

  suspend fun loadNetwork(): Network =
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
          .flatMap { trip -> boardingStopTimes(stopTimesByTrip[trip.tripId].orEmpty()) }
          .mapNotNullTo(mutableSetOf()) { it.stopId }
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
        timeZone = TimeZone.of(agencies.first().agencyTimezone),
        tripsByRoute = tripsByRoute,
        stopTimesByTrip = stopTimesByTrip,
        firstStopTimeByTrip =
          stopTimesByTrip.mapValues { (_, times) -> times.minBy { it.stopSequence } },
        calendars = calendars,
      )
    }

  internal fun boardingStopTimes(times: List<StopTime>): List<StopTime> =
    times.sortedBy { it.stopSequence }.dropLast(1).filter { it.allowsBoarding }

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

  /** Upcoming departures, including overnight trips from the previous service day. */
  fun routeDepartures(
    network: Network,
    routeId: String,
    now: Instant = Clock.System.now(),
    count: Int = 3,
  ): RouteDepartures {
    val today = now.toLocalDateTime(network.timeZone).date
    val firstTerminalSailings = mutableListOf<Sailing>()
    val sailingsByStopId = mutableMapOf<String, MutableList<Sailing>>()
    listOf(today.plus(-1, DateTimeUnit.DAY), today, today.plus(1, DateTimeUnit.DAY)).forEach { date
      ->
      val services = activeServiceIds(network, date)
      for (trip in network.tripsByRoute[routeId].orEmpty()) {
        if (trip.serviceId !in services) continue
        network.firstStopTimeByTrip[trip.tripId]?.departureTime?.let { departure ->
          val sailing =
            Sailing(departure.toInstant(date, network.timeZone), trip.tripHeadsign ?: "")
          if (sailing.instant >= now) firstTerminalSailings += sailing
        }

        for (stopTime in boardingStopTimes(network.stopTimesByTrip[trip.tripId].orEmpty())) {
          val stopId = stopTime.stopId ?: continue
          val departure = stopTime.departureTime
          if (departure != null && stopTime.allowsBoarding) {
            val sailing =
              Sailing(
                instant = departure.toInstant(date, network.timeZone),
                headsign = stopTime.stopHeadsign ?: trip.tripHeadsign ?: "",
              )
            if (sailing.instant >= now) {
              sailingsByStopId.getOrPut(stopId) { mutableListOf() }.add(sailing)
            }
          }
        }
      }
    }
    val upcoming = sailingsByStopId.mapValues { (_, sailings) ->
      sailings.distinct().sortedBy { it.instant }.take(count)
    }
    return RouteDepartures(
      routeId = routeId,
      nextSailings =
        firstTerminalSailings
          .sortedBy { it.instant }
          .take(count)
          .map { formatSailing(it.instant, it.headsign, network.timeZone) },
      nextByStopId =
        upcoming.mapValues { (_, sailings) ->
          val sailing = sailings.first()
          formatSailing(sailing.instant, sailing.headsign, network.timeZone)
        },
      sailingsByStopId = upcoming,
    )
  }
}
