package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.map.FakeImageBitmap
import org.maplibre.compose.mlnffi.runPlainComposeUiTest
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.ResolvedLayerDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.rememberStyleComposition

@OptIn(ExperimentalTestApi::class)
class GenericLayerCompositionTest {
  @Test
  fun state_reads_in_the_properties_block_trigger_updates() = runPlainComposeUiTest {
    val recording = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    var opacity by mutableStateOf(0.5f)
    setContent {
      rememberStyleComposition(
        maybeStyle = recording,
        applyRevision = { style, revision -> reconciler.apply(style, revision) },
        content = { Layer("plugin", "plugin") { paint("plugin-opacity", const(opacity)) } },
      )
    }
    waitForIdle()
    runOnIdle { opacity = 0.25f }
    waitForIdle()
    assertEquals(
      JsonPrimitive(0.25f),
      recording.layers.getValue("plugin")["paint"]!!.jsonObject["plugin-opacity"],
    )
  }

  @Test
  fun json_properties_bypass_filtering_and_replace_unknown_root_changes() = runPlainComposeUiTest {
    val recording = RecordingStyleBinding(animatorDurationScaleState = mutableStateOf(0.5f))
    var additions = 0
    val binding =
      object : StyleBinding by recording {
        override fun addLayer(
          definition: ResolvedLayerDefinition,
          beforeLayerId: String,
        ): Boolean {
          additions++
          return recording.addLayer(definition, beforeLayerId)
        }

        override fun unsupportedLayerPropertyReason(layerType: String, name: String): String? =
          "unknown to Compose"
      }
    val reconciler = StyleReconciler()
    val original =
      Json.parseToJsonElement(
          """{"type":"plugin-mesh","metadata":{"custom":true},"custom":null,"paint":{"mesh-opacity":0.5,"mesh-opacity-transition":{"duration":400,"delay":20}},"minzoom":4}"""
        )
        .jsonObject
    var json by mutableStateOf(original)
    var visible by mutableStateOf(true)
    setContent {
      rememberStyleComposition(
        maybeStyle = binding,
        applyRevision = { style, revision -> reconciler.apply(style, revision) },
        content = {
          if (visible) {
            Layer("mesh", "plugin-mesh") {
              root("metadata", json.getValue("metadata"))
              root("custom", json.getValue("custom"))
              json["minzoom"]?.let { root("minzoom", it) }
              (json["paint"] as? JsonObject)?.forEach { (name, value) -> paint(name, value) }
            }
          }
        },
      )
    }
    waitForIdle()
    assertEquals(
      JsonObject(
        original +
          ("id" to JsonPrimitive("mesh")) +
          ("paint" to
            JsonObject(
              original.getValue("paint").jsonObject +
                ("mesh-opacity-transition" to
                  Json.parseToJsonElement("""{"duration":200.0,"delay":10.0}"""))
            ))
      ),
      recording.layers.getValue("mesh"),
    )
    assertEquals(1, additions)
    runOnIdle { json = JsonObject(original - "paint" - "minzoom") }
    waitForIdle()
    assertEquals(1, additions)
    assertTrue(recording.layerPropertyWrites.contains("mesh" to "mesh-opacity"))
    assertEquals(JsonPrimitive(0), recording.layers.getValue("mesh")["minzoom"])
    runOnIdle { json = JsonObject(json + ("metadata" to JsonObject(emptyMap()))) }
    waitForIdle()
    assertEquals(2, additions)
    assertEquals(JsonObject(emptyMap()), recording.layers.getValue("mesh")["metadata"])
    runOnIdle { visible = false }
    waitForIdle()
    assertTrue(recording.layers.isEmpty())
  }

  @Test
  fun a_typed_plugin_wrapper_owns_sources_and_removes_conditional_properties() =
    runPlainComposeUiTest {
      val recording = RecordingStyleBinding()
      val reconciler = StyleReconciler()
      var opacity by mutableStateOf<Expression<FloatValue>?>(const(0.5f))
      var shown by mutableStateOf(true)
      setContent {
        rememberStyleComposition(
          maybeStyle = recording,
          applyRevision = { style, revision -> reconciler.apply(style, revision) },
          content = {
            if (shown) {
              val source =
                rememberGeoJsonSource(
                  GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}""")
                )
              PluginLayer("plugin", source, opacity)
            }
          },
        )
      }
      waitForIdle()
      assertEquals(1, recording.sources.size)
      assertEquals(1, recording.imageIds.size)
      assertEquals(
        JsonPrimitive(0.5f),
        recording.layers.getValue("plugin")["paint"]!!.jsonObject["plugin-opacity"],
      )
      runOnIdle { opacity = null }
      waitForIdle()
      assertTrue(recording.layerPropertyWrites.contains("plugin" to "plugin-opacity"))
      runOnIdle { shown = false }
      waitForIdle()
      assertTrue(recording.sources.isEmpty())
      assertTrue(recording.imageIds.isEmpty())
      assertTrue(recording.layers.isEmpty())
    }
}

private val pluginBitmap = FakeImageBitmap(2, 2)

// Uses only public layer APIs, as a separately published plugin would.
@Composable
private fun PluginLayer(id: String, source: Source, opacity: Expression<FloatValue>?) {
  Layer(id, "plugin", source) {
    if (opacity != null) paint("plugin-opacity", opacity)
    layout("plugin-image", image(pluginBitmap))
  }
}
