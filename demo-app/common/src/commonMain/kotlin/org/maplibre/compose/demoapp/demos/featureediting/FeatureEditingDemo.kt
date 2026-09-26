package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.demoapp.DefaultMapControls
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoMapControls
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.turf.measurement.computeBbox

/** A small editable shape makes Spatial K measurements and transformations visible. */
object FeatureEditingDemo : Demo {
  override val name = "Feature editing"
  override val description = "Draw a shape, measure it, and explore Spatial K transformations."
  override val destination =
    DemoDestination.FitBounds(Presets.goldenGatePark.geometry.computeBbox())
  private val editor = FeatureEditingState()

  override fun interactions(mapState: MapState, settings: MapInteractions) =
    MapInteractions(settings) {
      callbacks {
        click {
          onUnhandled { event ->
            if (editor.draft != null && event.position != null) {
              editor.place(checkNotNull(event.position))
              ClickResult.Consume
            } else ClickResult.Pass
          }
        }
      }
    }

  @Composable
  override fun MapContent(style: DemoStyle) {
    val shown = editor.displayed
    val scheme = MaterialTheme.colorScheme
    val problem = shown.problem()
    val color =
      if (problem != null && shown.vertices.size >= shown.kind.minimum) scheme.error
      else scheme.primary
    val geometry = shown.geometry ?: shown.vertices.takeIf { it.size >= 2 }?.let(::LineString)
    val source =
      rememberGeoJsonSource(
        GeoJsonData.Features(FeatureCollection(listOfNotNull(geometry?.let { Feature(it, null) }))),
        GeoJsonOptions(synchronousUpdate = true),
      )
    Anchor.Below({ it.type == "symbol" }) {
      if (editor.preview != null || editor.draft != null) {
        val original =
          rememberGeoJsonSource(
            GeoJsonData.Features(
              FeatureCollection(listOf(Feature(checkNotNull(editor.shape.geometry), null)))
            )
          )
        LineLayer(
          id = "editing-original",
          source = original,
          color = const(scheme.onSurfaceVariant.copy(alpha = 0.4f)),
          width = const(2.dp),
        )
      }
      if (geometry is Polygon) {
        FillLayer(id = "editing-fill", source = source, color = const(color.copy(alpha = 0.15f)))
      }
      LineLayer(id = "editing-line", source = source, color = const(color), width = const(3.dp))
    }
    Anchor.Top {
      val vertices =
        rememberGeoJsonSource(
          GeoJsonData.Features(
            FeatureCollection(
              shown.vertices.mapIndexed { index, position ->
                Feature(Point(position), null, id = JsonPrimitive(index))
              }
            )
          ),
          GeoJsonOptions(synchronousUpdate = true),
        )
      CircleLayer(
        id = "editing-vertices",
        source = vertices,
        radius = const(5.dp),
        color = const(scheme.surface),
        strokeColor = const(color),
        strokeWidth = const(2.dp),
      )
      val selected = shown.vertices.getOrNull(editor.selectedVertex ?: -1)
      val active =
        rememberGeoJsonSource(
          GeoJsonData.Features(
            FeatureCollection(listOfNotNull(selected?.let { Feature(Point(it), null) }))
          )
        )
      CircleLayer(
        id = "editing-active",
        source = active,
        radius = const(8.dp),
        color = const(color.copy(alpha = 0.2f)),
        strokeColor = const(color),
        strokeWidth = const(2.dp),
      )
    }
  }

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState, controls: DemoMapControls) {
    Box(Modifier.fillMaxSize().vertexInput(editor, state.mapState))
    DefaultMapControls(controls)
  }

  @Composable override fun PeekPanel(state: DemoAppState) = EditingActions(editor)

  @Composable override fun Panel(state: DemoAppState) = EditingPanel(editor, state)
}
