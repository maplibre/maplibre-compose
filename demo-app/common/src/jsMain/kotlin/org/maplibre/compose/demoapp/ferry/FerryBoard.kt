@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.maplibre.compose.demoapp.ferry

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Article
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Footer
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Text
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.Protomaps
import org.maplibre.compose.demoapp.demos.FerryMapContent
import org.maplibre.compose.demoapp.demos.FerrySchedule
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.WebMapPresentation
import org.maplibre.compose.map.createMapRuntime
import org.maplibre.spatialk.geojson.Position
import web.html.HTMLElement

@Composable
internal fun FerryBoard() {
  var network by remember { mutableStateOf<FerrySchedule.Network?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var attempt by remember { mutableStateOf(0) }
  var selectedId by remember { mutableStateOf<String?>(null) }
  var departures by remember { mutableStateOf<FerrySchedule.RouteDepartures?>(null) }
  var now by remember { mutableStateOf(Clock.System.now()) }
  var showMap by remember { mutableStateOf(true) }
  val runtime = remember { createMapRuntime(MapRuntimeOptions()) }
  val map = remember {
    runtime.createMapState(
      baseStyle = Protomaps.Light.base,
      cameraPosition = CameraPosition(target = Position(-122.7, 48.0), zoom = 7.2),
    ) {
      network?.let { FerryMapContent(it, selectedId, Protomaps.Light) }
    }
  }
  val selected = network?.routes?.find { it.id == selectedId }
  DisposableEffect(Unit) {
    document.title = "Washington ferries · Departures"
    onDispose {
      map.close()
      runtime.close()
    }
  }
  LaunchedEffect(attempt) {
    error = null
    try {
      network = FerrySchedule.loadNetwork()
      selectedId =
        network?.routes?.firstOrNull { it.displayName.contains("Seattle", ignoreCase = true) }?.id
          ?: network?.routes?.firstOrNull()?.id
    } catch (e: CancellationException) {
      throw e
    } catch (_: Throwable) {
      error = "Couldn't load the ferry schedule. Check your connection and try again."
    }
  }
  LaunchedEffect(network, selectedId) {
    departures = null
    val data = network ?: return@LaunchedEffect
    val id = selectedId ?: return@LaunchedEffect
    while (true) {
      now = Clock.System.now()
      departures = FerrySchedule.routeDepartures(data, id, now)
      delay(30.seconds)
    }
  }
  fun animation() =
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches)
      CameraAnimation.Ease(Duration.ZERO)
    else CameraAnimation.Ease()
  LaunchedEffect(selectedId) {
    selected?.let {
      map.animateCameraToBounds(
        boundingBox = it.bounds,
        fitPadding = PaddingValues(horizontal = 80.dp, vertical = 48.dp),
        animation = animation(),
      )
    }
  }

  Div({ classes("ferry-app") }) {
    Header({ classes("ferry-header") }) {
      Div {
        H1 { Text("Washington ferries") }
        P { Text("Scheduled departures · Pacific time") }
      }
      A(href = "?", attrs = { classes("demo-link") }) { Text("Map demos") }
    }
    Main({
      classes("ferry-layout")
      if (!showMap) classes("without-map")
    }) {
      Section({
        classes("ferry-board")
        attr("aria-label", "Ferry departures")
      }) {
        Label(forId = "ferry-route") { Text("Route") }
        Select({
          id("ferry-route")
          if (network == null) attr("disabled", "")
          onChange { selectedId = it.value }
        }) {
          if (network == null) Option("") { Text("Loading routes…") }
          network?.routes?.forEach { route ->
            Option(route.id, { if (route.id == selectedId) attr("selected", "") }) {
              Text(route.displayName)
            }
          }
        }
        Div({ classes("board-heading") }) {
          H2 { Text("Next departures") }
          Button({
            onClick { showMap = !showMap }
            attr("aria-pressed", showMap.toString())
          }) {
            Text(if (showMap) "Hide map" else "Show map")
          }
        }
        when {
          error != null ->
            Div({ attr("role", "alert") }) {
              P { Text(error!!) }
              Button({ onClick { attempt++ } }) { Text("Try again") }
            }
          network == null -> P({ attr("role", "status") }) { Text("Loading the ferry schedule…") }
          network?.routes?.isEmpty() == true ->
            P { Text("No ferry routes are available in this schedule.") }
          else -> {
            val data = network!!
            val times = departures?.takeIf { it.routeId == selectedId }
            data.stopIdsByRoute[selectedId]
              .orEmpty()
              .mapNotNull { data.terminalsById[it] }
              .sortedBy { it.name }
              .forEach { terminal ->
                Article({ classes("terminal") }) {
                  H3 { Text("From ${terminal.name}") }
                  val sailings = times?.sailingsByStopId?.get(terminal.id).orEmpty()
                  if (sailings.isEmpty()) {
                    P {
                      Text(
                        if (times == null) "Checking departures…"
                        else "No scheduled sailing through tomorrow"
                      )
                    }
                  }
                  sailings.forEach { sailing ->
                    val local = sailing.instant.toLocalDateTime(data.timeZone)
                    val hhmm =
                      "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
                    Div({ classes("sailing") }) {
                      P({ classes("departure-time") }) { Text(hhmm) }
                      Div {
                        P({ classes("destination") }) { Text(sailing.headsign) }
                        P({ classes("sailing-day") }) {
                          Text(
                            if (local.date == now.toLocalDateTime(data.timeZone).date) "Today"
                            else "Tomorrow"
                          )
                        }
                      }
                    }
                  }
                }
              }
          }
        }
        P({ classes("schedule-note") }) {
          Text("Scheduled times only. Check ")
          A("https://wsdot.com/ferries/schedule/bulletin.aspx") { Text("WSDOT service alerts") }
          Text(" before travelling.")
        }
      }
      if (showMap)
        Section({
          classes("ferry-map-panel")
          attr("aria-label", "Route map")
        }) {
          Div({
            classes("ferry-map")
            ref { element ->
              val presentation = WebMapPresentation(map)
              presentation.attachContainer(element.unsafeCast<HTMLElement>())
              onDispose { presentation.close() }
            }
          })
          Div({ classes("map-attribution") }) {
            A("https://protomaps.com") { Text("Protomaps") }
            Text(" · ")
            A("https://www.openstreetmap.org/copyright") { Text("© OpenStreetMap") }
          }
        }
    }
    Footer {
      Text("Schedule: ")
      A("https://www.wsdot.wa.gov/ferries/") {
        Text("Washington State Ferries")
      }
      Text(" via ")
      A("https://mobilitydatabase.org/feeds/gtfs/mdb-283") { Text("Mobility Database") }
    }
  }
}
