package org.maplibre.compose.demoapp.auto

import android.content.res.Configuration
import android.text.Html
import android.text.style.URLSpan
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.annotations.RequiresCarApi
import androidx.car.app.model.Action
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.Template
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.maplibre.compose.demoapp.car.CarMapDemo
import org.maplibre.compose.map.MapRuntime
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.overlay.attributions

/** Native Android Auto templates for the shared car map demo. */
@RequiresCarApi(7)
internal class MapScreen(carContext: CarContext, runtime: MapRuntime) : Screen(carContext) {
  private val demo = CarMapDemo(runtime, lifecycleScope, carContext.isDarkMode)
  private val map = CarMapSurface(carContext, lifecycle, demo.state)
  private val template =
    MapTemplate(
      carContext,
      onCredits = { screenManager.push(MapCreditsScreen(carContext, demo.state)) },
      onZoom = demo::zoom,
      onRecenter = demo::recenter,
    )

  init {
    lifecycle.addObserver(
      object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = demo.stop()

        override fun onDestroy(owner: LifecycleOwner) {
          demo.close()
          owner.lifecycle.removeObserver(this)
        }
      }
    )
    lifecycleScope.launch {
      snapshotFlow {
        Pair(map.presentation.failure, demo.state.style.loadState)
      }
        .collect { invalidate() }
    }
  }

  override fun onGetTemplate(): Template =
    template.build(
      error =
        map.presentation.failure != null || demo.state.style.loadState is StyleLoadState.Failed
    )

  fun updateConfiguration(configuration: Configuration) {
    if (demo.state.isClosed) return
    demo.updateDarkMode(carContext.isDarkMode)
    map.updateConfiguration(configuration)
  }
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
