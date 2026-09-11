package org.maplibre.compose.demoapp.auto

import android.content.res.Configuration
import android.text.Html
import android.text.style.URLSpan
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.annotations.RequiresCarApi
import androidx.car.app.model.Action
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.Template
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.Protomaps
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapRuntime
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.overlay.attributions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Position

/** Browses a small set of places using car templates and the shared demo's day/night styles. */
@RequiresCarApi(7)
internal class PlacesScreen(carContext: CarContext, runtime: MapRuntime) : Screen(carContext) {
  private var cameraAnimation: Job? = null
  private var inFlightZoom: PendingZoom? = null
  private var selectedPlace by mutableStateOf(PLACES.first())
  private var dark by mutableStateOf(carContext.isDarkMode)
  private val state =
    runtime.createMapState(baseStyle = mapStyle().base, cameraPosition = selectedPlace.camera) {
      PLACES.forEachIndexed { index, place ->
        val selected = place == selectedPlace
        CircleLayer(
          id = "car-place-$index",
          source = rememberGeoJsonSource(GeoJsonData.JsonString(place.geoJson)),
          radius = const(if (selected) 10.dp else 6.dp),
          color = const(if (dark) Color(0xFFFFD180) else Color(0xFF1565C0)),
          strokeColor = const(if (dark) Color(0xFF212121) else Color.White),
          strokeWidth = const(2.dp),
          onClick = {
            select(place)
            CarToast.makeText(carContext, place.name, CarToast.LENGTH_SHORT).show()
            ClickResult.Consume
          },
        )
      }
    }
  private val map = CarMapSurface(carContext, lifecycle, state)
  private val template =
    PlacesTemplate(
      carContext,
      onSelect = ::select,
      onCredits = { screenManager.push(MapCreditsScreen(carContext, state)) },
      onZoom = ::zoom,
      onRecenter = { select(selectedPlace) },
    )

  init {
    lifecycle.addObserver(
      object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
          cameraAnimation?.cancel()
          inFlightZoom = null
        }

        override fun onDestroy(owner: LifecycleOwner) {
          state.close()
          owner.lifecycle.removeObserver(this)
        }
      }
    )
    lifecycleScope.launch {
      snapshotFlow { state.cameraMoveReason }
        .collect { reason ->
          if (reason == CameraMoveReason.GESTURE) inFlightZoom = null
        }
    }
    lifecycleScope.launch {
      snapshotFlow {
        Triple(map.presentation.failure, state.style.loadState, state.style.attributions())
      }
        .collect { invalidate() }
    }
  }

  override fun onGetTemplate(): Template =
    template.build(
      selectedPlace = selectedPlace,
      loading = state.style.loadState != StyleLoadState.Ready,
      error = map.presentation.failure != null || state.style.loadState is StyleLoadState.Failed,
    )

  fun updateConfiguration(configuration: Configuration) {
    if (state.isClosed) return
    dark = carContext.isDarkMode
    checkNotNull(state.style.asMutable).baseStyle = mapStyle().base
    map.updateConfiguration(configuration)
  }

  private fun select(place: DemoPlace) {
    selectedPlace = place
    inFlightZoom = null
    cameraAnimation?.cancel()
    cameraAnimation = lifecycleScope.launch {
      state.animateCameraPosition(place.camera, CameraAnimation.Fly(650.milliseconds))
    }
    invalidate()
  }

  private fun zoom(levels: Int) {
    val from = inFlightZoom?.takeIf { it.levels == levels }?.target ?: state.cameraPosition
    val request = PendingZoom(levels, from.copy(zoom = from.zoom + levels))
    cameraAnimation?.cancel()
    inFlightZoom = request
    cameraAnimation = lifecycleScope.launch {
      try {
        state.animateCameraPosition(request.target, CameraAnimation.Ease())
      } finally {
        if (inFlightZoom === request) inFlightZoom = null
      }
    }
  }

  private data class PendingZoom(val levels: Int, val target: CameraPosition)

  private fun mapStyle(): Protomaps = if (dark) Protomaps.Dark else Protomaps.Light
}

private class MapCreditsScreen(carContext: CarContext, private val state: MapState) :
  Screen(carContext) {
  init {
    lifecycleScope.launch { snapshotFlow { state.style.attributions() }.collect { invalidate() } }
  }

  override fun onGetTemplate(): Template {
    val credits =
      state.style
        .attributions()
        .joinToString("\n\n") { html ->
          val text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
          val links = text.getSpans(0, text.length, URLSpan::class.java).map { it.url }.distinct()
          (listOf(text.toString().trim()) + links).joinToString("\n")
        }
        .ifBlank { carContext.getString(R.string.map_credits_loading) }
    return LongMessageTemplate.Builder(credits)
      .setTitle(carContext.getString(R.string.map_credits))
      .setHeaderAction(Action.BACK)
      .build()
  }
}

internal data class DemoPlace(
  val name: String,
  val description: String,
  val longitude: Double,
  val latitude: Double,
) {
  val camera: CameraPosition
    get() = CameraPosition(target = Position(longitude, latitude), zoom = 14.0)

  val geoJson: String
    get() =
      """{"type":"Feature","geometry":{"type":"Point","coordinates":[$longitude,$latitude]},"properties":{}}"""
}

internal val PLACES =
  listOf(
    DemoPlace("Central Park", "Manhattan", -73.9654, 40.7829),
    DemoPlace("Brooklyn Bridge Park", "Brooklyn waterfront", -73.9969, 40.7024),
    DemoPlace("Prospect Park", "Brooklyn", -73.968, 40.6602),
  )
