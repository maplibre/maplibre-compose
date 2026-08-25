package org.maplibre.compose.layers

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withRunningRecomposer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.compose.expressions.dsl.textVariableAnchorOffset
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.FakeStyle
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.style.SafeStyle
import org.maplibre.compose.style.StyleContent
import org.maplibre.compose.style.StyleNode
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class SymbolLayerCompositionTest {

  @Test
  fun variable_anchor_offsets_reach_the_layer_through_the_public_api() = runTest {
    val frameClock = BroadcastFrameClock()
    withContext(frameClock) {
      withRunningRecomposer { recomposer ->
        val style = FakeStyle(emptyList(), emptyList(), emptyList())
        val rootNode = StyleNode(SafeStyle(style), logger = null)
        val source =
          GeoJsonSource(
            "features",
            GeoJsonData.Features(featureCollectionOf()),
            GeoJsonOptions(),
          )
        val composition = Composition(MapNodeApplier(rootNode), recomposer)
        try {
          composition.setContent {
            CompositionLocalProvider(
              LocalDensity provides Density(1f),
              LocalLayoutDirection provides LayoutDirection.Ltr,
            ) {
              StyleContent(rootNode) {
                SymbolLayer(
                  id = "labels",
                  source = source,
                  textVariableAnchorOffset =
                    textVariableAnchorOffset(
                      SymbolAnchor.Top to Offset(0f, 1f),
                      SymbolAnchor.Bottom to Offset(0f, -2f),
                    ),
                )
              }
            }
          }
          while (!frameClock.hasAwaiters) yield()
          frameClock.sendFrame(0)
          yield()
          recomposer.awaitIdle()

          val layer = assertNotNull(style.getLayer("labels"))
          val layout = assertNotNull(layer.toJson()["layout"] as? JsonObject)
          assertEquals(
            Json.parseToJsonElement("""["literal",["top",[0,1],"bottom",[0,-2]]]""")
              .normalizeNumbers(),
            assertNotNull(layout["text-variable-anchor-offset"]).normalizeNumbers(),
          )
        } finally {
          composition.dispose()
        }
      }
    }
  }

  private fun JsonElement.normalizeNumbers(): JsonElement =
    when (this) {
      is JsonArray -> JsonArray(map { it.normalizeNumbers() })
      is JsonObject -> JsonObject(mapValues { (_, value) -> value.normalizeNumbers() })
      is JsonPrimitive -> doubleOrNull?.let(::JsonPrimitive) ?: this
    }
}
